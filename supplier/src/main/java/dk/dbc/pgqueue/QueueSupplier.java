/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-supplier
 *
 * dbc-pg-queue-supplier is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-supplier is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue;

import dk.dbc.pgqueue.common.QueueStorageAbstraction;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Optional;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 * @param <T> the job type
 */
public class QueueSupplier<T> {

    private final QueueStorageAbstraction<T> abstraction;
    private final String insertNowSql;
    private final String insertLaterSql;

    public QueueSupplier(QueueStorageAbstraction<T> storageAbstraction) {
        this.abstraction = storageAbstraction;
        String columns = String.join(", ", abstraction.columnList());
        String placeholders = String.join(", ", Collections.nCopies(abstraction.columnList().length, "?"));
        this.insertNowSql = "INSERT INTO queue(consumer, " +
                            columns +
                            ") VALUES(?, " +
                            placeholders +
                            ")";
        this.insertLaterSql = "INSERT INTO queue(consumer, dequeueAfter, " +
                              columns +
                              ") VALUES(?, clock_timestamp() + ? * INTERVAL '1 MILLISECONDS', " +
                              placeholders +
                              ")";
    }

    /**
     * Create a supplier with (lazy) prepares statements
     *
     * @param connection database connection to enqueue upon
     * @return object with prepared statements placeholders
     */
    public PreparedQueueSupplier<T> preparedSupplier(Connection connection) {
        return new PreparedQueueSupplier<>(abstraction, connection, insertNowSql, insertLaterSql);
    }

    /**
     * Create a supplier with (lazy) prepares statements, and batching
     * <p>
     * The last batch is sent when this is closed
     *
     * @param connection database connection to enqueue upon
     * @param batchSize  execute every n enqueues (different counters for
     *                   postponed/now)
     * @return object with prepared statements placeholders
     */
    public BatchQueueSupplier<T> batchSupplier(Connection connection, int batchSize) {
        return new BatchQueueSupplier<>(abstraction, connection, insertNowSql, insertLaterSql, batchSize);
    }

    /**
     * Create a supplier with (lazy) prepares statements, and batching
     * <p>
     * The batch is sent when this is closed
     *
     * @param connection database connection to enqueue upon
     * @return object with prepared statements placeholders
     */
    public BatchQueueSupplier<T> batchSupplier(Connection connection) {
        return new BatchQueueSupplier<>(abstraction, connection, insertNowSql, insertLaterSql, -1);
    }

    /**
     * Enqueue a job
     * <p>
     * This should be avoided unless only called once this transaction
     *
     * @see PreparedQueueSupplier
     * <p>
     * This is a wrapper around {@link #preparedSupplier(java.sql.Connection) }
     * and
     * {@link PreparedQueueSupplier#enqueue(java.lang.String, java.lang.Object)}
     *
     * @param connection database connection
     * @param queue      name of queue
     * @param job        the job to queue
     * @throws SQLException in case of communicating with database errors
     */
    public void enqueue(Connection connection, String queue, T job) throws SQLException {
        preparedSupplier(connection).enqueue(queue, job);
    }

    /**
     * Enqueue a job for delayed dequeuing
     * <p>
     * This should be avoided unless only called once this transaction
     *
     * @see PreparedQueueSupplier
     * <p>
     * This is a wrapper around {@link #preparedSupplier(java.sql.Connection) }
     * and
     * {@link PreparedQueueSupplier#enqueue(java.lang.String, java.lang.Object, long)}
     *
     * @param connection database connection
     * @param queue      name of queue
     * @param job        the job to queue
     * @param postponed  in how many milliseconds
     * @throws SQLException in case of communicating with database errors
     */
    public void enqueue(Connection connection, String queue, T job, long postponed) throws SQLException {
        preparedSupplier(connection).enqueue(queue, job, postponed);

    }

    static Optional<SQLException> wrapSql(String errorMessage, VoidBlock block) {
        try {
            block.run();
            return Optional.empty();
        } catch (SQLException ex) {
            return Optional.of(new SQLException(errorMessage, ex));
        }
    }

    @FunctionalInterface
    interface VoidBlock {

        void run() throws SQLException;
    }

}
