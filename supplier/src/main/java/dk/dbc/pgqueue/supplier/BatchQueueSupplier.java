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
package dk.dbc.pgqueue.supplier;

import dk.dbc.pgqueue.common.QueueStorageAbstraction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 * @param <T> the job type
 */
public class BatchQueueSupplier<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchQueueSupplier.class);

    private final QueueStorageAbstraction<T> abstraction;
    private final Connection connection;
    private final String insertNowSql;
    private final String insertLaterSql;
    private PreparedStatement insertNowStmt;
    private PreparedStatement insertLaterStmt;
    private final int executeEvery;
    private int toGoNow;   // 0 in these ha magic meaning: nothing has been queues
    private int toGoLater; // since lase executeBatch(). ie. no need to executeBatch() upon close()

    BatchQueueSupplier(QueueStorageAbstraction<T> abstraction, Connection connection, String insertNowSql, String insertLaterSql, int executeEvery) {
        this.abstraction = abstraction;
        this.connection = connection;
        this.insertNowSql = insertNowSql;
        this.insertLaterSql = insertLaterSql;
        this.insertNowStmt = null;
        this.insertLaterStmt = null;
        this.executeEvery = executeEvery;
        this.toGoNow = 0;
        this.toGoLater = 0;
    }

    /**
     * enqueue a job
     * <p>
     * This might send the job to the database, or be postponed to later
     * delivery by {@link BatchQueueSupplier#close()}
     *
     * @param queue name of queue
     * @param job   the job to queue
     * @throws SQLException in case of communicating with database errors
     */
    public void enqueue(String queue, T job) throws SQLException {
        PreparedStatement stmt = getInsertNowStmt();
        int pos = 1;
        stmt.setString(pos++, queue);
        abstraction.saveJob(job, stmt, pos);
        stmt.addBatch();
        if (toGoNow == 0)
            toGoNow = executeEvery;
        if (--toGoNow == 0) {
            log.debug("Sending 'now' batch");
            stmt.executeBatch();
        }
    }

    /**
     * enqueue a job for delayed dequeuing
     * <p>
     * This might send the job to the database, or be postponed to later
     * delivery by {@link BatchQueueSupplier#close()}
     *
     * @param queue     name of queue
     * @param job       the job to queue
     * @param postponed in how many milliseconds
     * @throws SQLException in case of communicating with database errors
     */
    public void enqueue(String queue, T job, long postponed) throws SQLException {
        PreparedStatement stmt = getInsertLaterStmt();
        int pos = 1;
        stmt.setString(pos++, queue);
        stmt.setLong(pos++, postponed);
        abstraction.saveJob(job, stmt, pos);
        stmt.addBatch();
        if (toGoLater == 0)
            toGoLater = executeEvery;
        if (--toGoLater == 0) {
            log.debug("Sending 'later' batch");
            stmt.executeBatch();
        }
    }

    private PreparedStatement getInsertNowStmt() throws SQLException {
        if (insertNowStmt == null) {
            insertNowStmt = connection.prepareStatement(insertNowSql);
        }
        return insertNowStmt;
    }

    private PreparedStatement getInsertLaterStmt() throws SQLException {
        if (insertLaterStmt == null) {
            insertLaterStmt = connection.prepareStatement(insertLaterSql);
        }
        return insertLaterStmt;
    }

    /**
     * Close statements and send (last) batches
     *
     * @throws SQLException The first encountered exception when trying to close
     *                      finalize the inserts
     */
    @Override
    public void close() throws SQLException {
        List<Optional<SQLException>> exceptions = new ArrayList<>(4);

        if (insertNowStmt != null) {
            if (toGoNow != 0) {
                log.debug("Sending 'now' batch");
                exceptions.add(QueueSupplier.wrapSql("Execute enqueue now", () -> insertNowStmt.executeBatch()));
            }
            exceptions.add(QueueSupplier.wrapSql("Close enqueue now statement", () -> insertNowStmt.close()));
        }
        if (insertLaterStmt != null) {
            if (toGoLater != 0) {
                log.debug("Sending 'later' batch");
                exceptions.add(QueueSupplier.wrapSql("Execute enqueue later", () -> insertLaterStmt.executeBatch()));
            }
            exceptions.add(QueueSupplier.wrapSql("Close enqueue later statement", () -> insertLaterStmt.close()));
        }
        Optional<SQLException> firstException = exceptions.stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (firstException.isPresent())
            throw firstException.get();
    }
}
