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
package org.neo4j.kernel.impl.transaction;

import static org.neo4j.common.Subject.ANONYMOUS;
import static org.neo4j.kernel.impl.transaction.log.LogIndexEncoding.decodeLogIndex;

import java.io.IOException;
import java.util.List;
import org.neo4j.io.fs.WritableChecksumChannel;
import org.neo4j.kernel.impl.transaction.log.CompleteTransaction;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogicalTransactionStore;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryCommit;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryStart;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryWriter;
import org.neo4j.storageengine.api.CommandBatch;
import org.neo4j.storageengine.api.StorageCommand;

/**
 * This class represents the concept of a TransactionRepresentation that has been
 * committed to the TransactionStore. It contains, in addition to the TransactionRepresentation
 * itself, a Start and Commit entry. This is the thing that {@link LogicalTransactionStore} returns when
 * asked for a transaction via a cursor.
 */
public record CommittedTransactionRepresentation(
        LogEntryStart startEntry, CommandBatch commandBatch, LogEntryCommit commitEntry)
        implements CommittedCommandBatch {

    public CommittedTransactionRepresentation(
            LogEntryStart startEntry, List<StorageCommand> commands, LogEntryCommit commitEntry) {
        this(
                startEntry,
                new CompleteTransaction(
                        commands,
                        decodeLogIndex(startEntry.getAdditionalHeader()),
                        startEntry.getTimeWritten(),
                        startEntry.getLastCommittedTxWhenTransactionStarted(),
                        commitEntry.getTimeWritten(),
                        -1,
                        startEntry.kernelVersion(),
                        ANONYMOUS),
                commitEntry);
    }

    @Override
    public int serialize(LogEntryWriter<? extends WritableChecksumChannel> writer) throws IOException {
        byte version = startEntry.kernelVersion().version();
        writer.writeStartEntry(
                version,
                startEntry.getTimeWritten(),
                startEntry.getLastCommittedTxWhenTransactionStarted(),
                startEntry.getPreviousChecksum(),
                startEntry.getAdditionalHeader());
        writer.serialize(commandBatch);
        return writer.writeCommitEntry(version, commitEntry.getTxId(), commitEntry.getTimeWritten());
    }

    @Override
    public int checksum() {
        return commitEntry.getChecksum();
    }

    @Override
    public long timeWritten() {
        return commitEntry.getTimeWritten();
    }

    @Override
    public long txId() {
        return commitEntry.getTxId();
    }

    @Override
    public boolean isRollback() {
        return false;
    }

    @Override
    public LogPosition previousBatchLogPosition() {
        return LogPosition.UNSPECIFIED;
    }

    @Override
    public String toString() {
        return "CommittedTransactionRepresentation{" + "startEntry="
                + startEntry + ", transactionRepresentation="
                + commandBatch + ", commitEntry="
                + commitEntry + '}';
    }
}
