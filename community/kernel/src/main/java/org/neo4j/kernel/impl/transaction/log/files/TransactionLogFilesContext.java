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
package org.neo4j.kernel.impl.transaction.log.files;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.DbmsRuntimeRepository;
import org.neo4j.internal.nativeimpl.NativeAccess;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.impl.transaction.log.LogTailMetadata;
import org.neo4j.logging.InternalLogProvider;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.monitoring.DatabaseHealth;
import org.neo4j.monitoring.Monitors;
import org.neo4j.storageengine.api.CommandReaderFactory;
import org.neo4j.storageengine.api.KernelVersionRepository;
import org.neo4j.storageengine.api.LegacyStoreId;

public class TransactionLogFilesContext
{
    private final AtomicLong rotationThreshold;
    private final AtomicBoolean tryPreallocateTransactionLogs;
    private final CommandReaderFactory commandReaderFactory;
    private final LastCommittedTransactionIdProvider lastCommittedTransactionIdSupplier;
    private final LongSupplier committingTransactionIdSupplier;
    private final LastClosedPositionProvider lastClosedPositionProvider;
    private final LogVersionRepositoryProvider logVersionRepositoryProvider;
    private final FileSystemAbstraction fileSystem;
    private final InternalLogProvider logProvider;
    private final DatabaseTracers databaseTracers;
    private final NativeAccess nativeAccess;
    private final MemoryTracker memoryTracker;
    private final Monitors monitors;
    private final boolean failOnCorruptedLogFiles;
    private final Supplier<LegacyStoreId> storeId;
    private final DatabaseHealth databaseHealth;
    private final KernelVersionRepository kernelVersionRepository;
    private final Clock clock;
    private final String databaseName;
    private final Config config;
    private final LogTailMetadata externalTailInfo;
    private final DbmsRuntimeRepository dbmsRuntimeRepository;

    public TransactionLogFilesContext( AtomicLong rotationThreshold, AtomicBoolean tryPreallocateTransactionLogs, CommandReaderFactory commandReaderFactory,
            LastCommittedTransactionIdProvider lastCommittedTransactionIdSupplier, LongSupplier committingTransactionIdSupplier,
            LastClosedPositionProvider lastClosedPositionProvider,
            LogVersionRepositoryProvider logVersionRepositoryProvider, FileSystemAbstraction fileSystem, InternalLogProvider logProvider,
            DatabaseTracers databaseTracers, Supplier<LegacyStoreId> storeId, NativeAccess nativeAccess,
            MemoryTracker memoryTracker, Monitors monitors, boolean failOnCorruptedLogFiles, DatabaseHealth databaseHealth,
            KernelVersionRepository kernelVersionRepository, Clock clock, String databaseName, Config config, LogTailMetadata externalTailInfo,
            DbmsRuntimeRepository dbmsRuntimeRepository )
    {
        this.rotationThreshold = rotationThreshold;
        this.tryPreallocateTransactionLogs = tryPreallocateTransactionLogs;
        this.commandReaderFactory = commandReaderFactory;
        this.lastCommittedTransactionIdSupplier = lastCommittedTransactionIdSupplier;
        this.committingTransactionIdSupplier = committingTransactionIdSupplier;
        this.lastClosedPositionProvider = lastClosedPositionProvider;
        this.logVersionRepositoryProvider = logVersionRepositoryProvider;
        this.fileSystem = fileSystem;
        this.logProvider = logProvider;
        this.databaseTracers = databaseTracers;
        this.storeId = storeId;
        this.nativeAccess = nativeAccess;
        this.memoryTracker = memoryTracker;
        this.monitors = monitors;
        this.failOnCorruptedLogFiles = failOnCorruptedLogFiles;
        this.databaseHealth = databaseHealth;
        this.kernelVersionRepository = kernelVersionRepository;
        this.clock = clock;
        this.databaseName = databaseName;
        this.config = config;
        this.externalTailInfo = externalTailInfo;
        this.dbmsRuntimeRepository = dbmsRuntimeRepository;
    }

    AtomicLong getRotationThreshold()
    {
        return rotationThreshold;
    }

    public CommandReaderFactory getCommandReaderFactory()
    {
        return commandReaderFactory;
    }

    public LogVersionRepositoryProvider getLogVersionRepositoryProvider()
    {
        return logVersionRepositoryProvider;
    }

    public LastCommittedTransactionIdProvider getLastCommittedTransactionIdProvider()
    {
        return lastCommittedTransactionIdSupplier;
    }

    public long committingTransactionId()
    {
        return committingTransactionIdSupplier.getAsLong();
    }

    LastClosedPositionProvider getLastClosedTransactionPositionProvider()
    {
        return lastClosedPositionProvider;
    }

    public FileSystemAbstraction getFileSystem()
    {
        return fileSystem;
    }

    public InternalLogProvider getLogProvider()
    {
        return logProvider;
    }

    AtomicBoolean getTryPreallocateTransactionLogs()
    {
        return tryPreallocateTransactionLogs;
    }

    public NativeAccess getNativeAccess()
    {
        return nativeAccess;
    }

    public DatabaseTracers getDatabaseTracers()
    {
        return databaseTracers;
    }

    public LegacyStoreId getStoreId()
    {
        return storeId.get();
    }

    public MemoryTracker getMemoryTracker()
    {
        return memoryTracker;
    }

    public Monitors getMonitors()
    {
        return monitors;
    }

    public boolean isFailOnCorruptedLogFiles()
    {
        return failOnCorruptedLogFiles;
    }

    public DatabaseHealth getDatabaseHealth()
    {
        return databaseHealth;
    }

    public KernelVersionRepository getKernelVersionProvider()
    {
        return kernelVersionRepository;
    }

    public Clock getClock()
    {
        return clock;
    }

    public String getDatabaseName()
    {
        return databaseName;
    }

    public Config getConfig()
    {
        return config;
    }

    public LogTailMetadata getExternalTailInfo()
    {
        return externalTailInfo;
    }

    public DbmsRuntimeRepository getDbmsRuntimeRepository()
    {
        return dbmsRuntimeRepository;
    }
}
