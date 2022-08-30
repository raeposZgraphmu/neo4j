/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.commandline.dbms;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.join;
import static org.neo4j.configuration.Config.Builder.allowedMultipleDeclarations;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.PropertiesConfigurationLayout;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.eclipse.collections.impl.factory.Maps;
import org.neo4j.cli.CommandFailedException;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.SettingMigrator;
import org.neo4j.logging.NullLog;
import org.neo4j.service.Services;

/*
Currently the only known issue is that multiple declarations and their comments are put together so

# G1GC generally strikes a good balance between throughput and tail
# latency, without too much tuning.
dbms.jvm.additional=-XX:+UseG1GC

# Have common exceptions keep producing stack traces, so they can be
# debugged regardless of how often logs are rotated.
dbms.jvm.additional=-XX:-OmitStackTraceInFastThrow

becomes

# G1GC generally strikes a good balance between throughput and tail
# latency, without too much tuning.
# Have common exceptions keep producing stack traces, so they can be
# debugged regardless of how often logs are rotated.
server.jvm.additional=-XX:+UseG1GC
server.jvm.additional=-XX:-OmitStackTraceInFastThrow

"server.jvm.additional" is the only settings that is allowed to have  multiple declarations, so it is not that bad.

 */
class ConfigFileMigrator {

    /*
    The framework used for the migration, works a bit unexpected with comment separators.
    It uses \n regardless of the platform when building the layout and then, if on Windows, replaces
    \n with \r\n when writing to the file.
    So if we did the seemingly right thing and used platform-specific separator on this level,
    we would end up with \r\r\n on Windows.
    */
    private static final String COMMENT_LINE_SEPARATOR = "\n";

    private final PrintStream out;
    private final PrintStream err;
    private final Set<SettingMigrator> migrators = getSortedMigrators();
    private final Set<String> knownSettings = getKnownSettings(); // This is excluding group settings

    ConfigFileMigrator(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    private static Set<SettingMigrator> getSortedMigrators() {
        Set<SettingMigrator> migrators =
                new TreeSet<>(Comparator.comparing(o -> o.getClass().getName()));
        migrators.addAll(Services.loadAll(SettingMigrator.class));
        return migrators;
    }

    void migrate(Path sourceConfigFile, Path destinationConfigFile) throws IOException {
        try {
            PropertiesConfiguration config = readIntoConfig(sourceConfigFile);
            LoggingSettingsMigrator loggingSettingsMigrator =
                    new LoggingSettingsMigrator(sourceConfigFile, out, destinationConfigFile);
            loggingSettingsMigrator.migrate();
            migrateSettings(config);
            writeToFile(config, destinationConfigFile);
            validate(destinationConfigFile);
        } catch (ConfigurationException e) {
            throw new CommandFailedException(e.getMessage(), e);
        }
    }

    private void migrateSettings(PropertiesConfiguration config) {
        PropertiesConfigurationLayout layout = config.getLayout();
        // The pattern is always a comment followed by a setting.
        // If we end up commenting out a setting as part of migration,
        // that setting will become, from the used config library point of view,
        // a part of the comment belonging to the following setting.
        // The very similar situation is for leading empty lines.
        // If we end up commenting out a setting, the leading empty
        // lines of the commented out setting become the leading empty
        // lines of the following setting.
        StringBuilder comment = new StringBuilder();
        int leadingEmptyLines = 0;
        // Layout stores keys in insert-order. Take a copy (to avoid ConcurrentModificationException)
        List<String> originalKeysInOrder = new ArrayList<>(layout.getKeys());
        for (String originalKey : originalKeysInOrder) {
            List<String> originalValues = config.getList(String.class, originalKey);
            if (originalValues.isEmpty()) {
                // Settings with no value like "foo.bar=" are not present in the originalValues at all.
                // Let's create an artificial value for this case, so we don't have to handle this case everywhere.
                originalValues = List.of("");
            }

            // We remove and re-add every key/value anyway to preserve order
            // First, remember comment & free lines in case we need it
            String originalComment = layout.getComment(originalKey);
            int originalFreeLines = layout.getBlancLinesBefore(originalKey);
            config.clearProperty(originalKey);

            if (!comment.isEmpty()) {
                // If we have a comment from the previous key,
                // it means the previous setting was commented out.
                // This means that the free lines should be part of the comment
                // to be between the original previous setting and this one.
                comment.append(join(COMMENT_LINE_SEPARATOR.repeat(originalFreeLines)));
            } else {
                leadingEmptyLines = originalFreeLines;
            }

            if (isNotEmpty(originalComment)) {
                // This comment and the comment from the previous key
                // must be joined by a new line
                if (!comment.isEmpty()) {
                    comment.append(COMMENT_LINE_SEPARATOR);
                }
                comment.append(originalComment);
            }

            // Migrate setting, one at a time, to know exactly what's changing
            Optional<MigratedSetting> maybeMigratedSetting = migrate(
                    originalKey,
                    originalValues,
                    removedOriginalValue -> {
                        appendCommentedOutSetting(comment, originalKey, removedOriginalValue, "REMOVED");
                        out.printf("%s=%s REMOVED%n", originalKey, removedOriginalValue);
                    },
                    unrecognisedOriginalValue -> {
                        appendCommentedOutSetting(comment, originalKey, unrecognisedOriginalValue, "UNKNOWN");
                        err.printf("%s=%s REMOVED UNKNOWN%n", originalKey, unrecognisedOriginalValue);
                    });

            if (maybeMigratedSetting.isPresent()) {
                // What is left now are only valid settings, possibly migrated.
                MigratedSetting migratedSetting = maybeMigratedSetting.get();
                List<String> values = migratedSetting.values;
                String key = migratedSetting.key;

                if (values.size() > 1 && !allowedMultipleDeclarations(key)) {
                    // the last one wins and the ones before are commended out and marked as a duplicate
                    for (int i = 0; i < values.size() - 1; i++) {
                        appendCommentedOutSetting(comment, key, values.get(i), "DUPLICATE");
                        err.printf("%s=%s REMOVED DUPLICATE%n", key, values.get(i));
                    }

                    values = List.of(values.get(values.size() - 1));
                }

                config.setProperty(key, values);

                for (String value : values) {
                    String originalValue = migratedSetting.originalValues.get(value);
                    boolean unchanged = values.size() == 1
                            && Objects.equals(originalKey, key)
                            && Objects.equals(originalValue, value);
                    if (unchanged) {
                        out.printf("%s=%s UNCHANGED%n", originalKey, originalValue);
                    } else {
                        out.printf("%s=%s MIGRATED -> %s=%s%n", originalKey, originalValue, key, value);
                    }
                }

                layout.setBlancLinesBefore(key, leadingEmptyLines);
                layout.setComment(key, defaultIfBlank(comment.toString(), null));
                // Consumed the preserved data, reset it
                comment.setLength(0);
                leadingEmptyLines = 0;
            }
        }
        if (isNotEmpty(comment)) {
            layout.setFooterComment(
                    join(COMMENT_LINE_SEPARATOR.repeat(leadingEmptyLines), comment, layout.getFooterComment()));
        }
    }

    private void appendCommentedOutSetting(StringBuilder commentBuilder, String key, String value, String reason) {
        commentBuilder.append(COMMENT_LINE_SEPARATOR).append(format("%s=%s %s SETTING", key, value, reason));
    }

    private Optional<MigratedSetting> migrate(
            String originalKey,
            List<String> originalValues,
            Consumer<String> removedValueConsumer,
            Consumer<String> unrecognisedValueConsumer) {
        List<String> migratedValues = new ArrayList<>(originalValues.size());
        Map<String, String> originalValueMapping = new HashMap<>(originalValues.size());

        String migratedKey = null;
        for (String originalValue : originalValues) {
            Map<String, String> map = Maps.mutable.of(originalKey, originalValue);
            migrators.forEach(m -> m.migrate(map, Map.of(), NullLog.getInstance()));
            if (!map.isEmpty()) {
                var entry = map.entrySet().iterator().next();
                // Remove any unrecognized "garbage"
                if (isSettingValid(entry.getKey(), entry.getValue())) {
                    migratedKey = entry.getKey();
                    migratedValues.add(entry.getValue());
                    originalValueMapping.put(entry.getValue(), originalValue);
                } else {
                    unrecognisedValueConsumer.accept(originalValue);
                }
            } else {
                removedValueConsumer.accept(originalValue);
            }
        }

        if (migratedKey == null || migratedValues.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new MigratedSetting(migratedKey, migratedValues, originalValueMapping));
    }

    private boolean isSettingValid(String key, String value) {
        if (!knownSettings.contains(key)) {
            try { // The setting can be a group setting. The only way to know is to build the config and see
                Config.newBuilder()
                        .setRaw(Map.of(key, value))
                        .set(GraphDatabaseSettings.strict_config_validation, true)
                        .build();
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    private void validate(Path config) {
        try {
            Config.newBuilder()
                    .fromFile(config)
                    .set(GraphDatabaseSettings.strict_config_validation, true)
                    .build();
        } catch (RuntimeException e) {
            throw new CommandFailedException("Migrated file failed validation", e);
        }
    }

    private void writeToFile(PropertiesConfiguration config, Path configFile)
            throws ConfigurationException, IOException {
        StringWriter sw = new StringWriter();
        PropertiesConfigurationLayout layout = config.getLayout();
        // the default value for key-value separator is " = ", which is not how neo4j presents its config by default
        layout.setGlobalSeparator("=");
        layout.save(config, sw);
        if (Files.exists(configFile)) {
            Path preservedFilePath = configFile.getParent().resolve(configFile.getFileName() + ".old");
            out.println("Keeping original configuration file at: " + preservedFilePath);
            Files.move(configFile, preservedFilePath);
        }

        Files.writeString(configFile, sw.toString());
    }

    private PropertiesConfiguration readIntoConfig(Path configFile) throws IOException, ConfigurationException {
        try (InputStream stream = Files.newInputStream(configFile)) {
            PropertiesConfiguration config = new PropertiesConfiguration();
            config.getLayout().load(config, new InputStreamReader(stream));
            return config;
        }
    }

    private Set<String> getKnownSettings() {
        return Config.defaults().getDeclaredSettings().keySet();
    }

    private record MigratedSetting(String key, List<String> values, Map<String, String> originalValues) {}
}
