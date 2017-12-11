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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 * @param <T> the job type
 */
public class PreparedQueueSupplier<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PreparedQueueSupplier.class);

    private final QueueStorageAbstraction<T> abstraction;
    private final Connection connection;
    private final String insertNowSql;
    private final String insertLaterSql;
    private PreparedStatement insertNowStmt;
    private PreparedStatement insertLaterStmt;

    PreparedQueueSupplier(QueueStorageAbstraction<T> abstraction, Connection connection, String insertNowSql, String insertLaterSql) {
        this.abstraction = abstraction;
        this.connection = connection;
        this.insertNowSql = insertNowSql;
        this.insertLaterSql = insertLaterSql;
        this.insertNowStmt = null;
        this.insertLaterStmt = null;
    }

    /**
     * enqueue a job
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
        stmt.executeUpdate();
    }

    /**
     * enqueue a job for delayed dequeuing
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
        stmt.executeUpdate();
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

    @Override
    public void close() throws Exception {
        if (insertNowStmt != null) {
            try {
                insertNowStmt.close();
            } catch (SQLException ex) {
                log.error("Error closing insert now statement: {}", ex.getMessage());
                log.debug("Error closing insert now statement:", ex);
            }
        }
        if (insertLaterStmt != null) {
            try {
                insertLaterStmt.close();
            } catch (SQLException ex) {
                log.error("Error closing insert later statement: {}", ex.getMessage());
                log.debug("Error closing insert later statement:", ex);
            }
        }
    }

}
