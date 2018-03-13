/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-consumer
 *
 * dbc-pg-queue-consumer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-consumer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.consumer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Queue job container
 * <p>
 * Wraps a queue job, and has metadata about it
 *
 * @author DBC {@literal <dbc.dk>}
 *
 */
public class JobMetaData {

    static final String COLUMNS = "consumer, queued, dequeueAfter, tries";
    static final int COLUMN_COUNT = 4;
    static final String RETRY_PLACEHOLDER = "?, ?, ?, ?";
    static final int RETRY_PLACEHOLDER_COUNT = 4;
    static final String POSTPONED_PLACEHOLDER = "?, ?, clock_timestamp() + ? * INTERVAL '1 MILLISECONDS', ?";
    static final int POSTPONED_PLACEHOLDER_COUNT = 4;

    private final String consumer;
    private final Timestamp queued;
    private final Timestamp dequeueAfter;
    private final int tries;

    /**
     * Construct a job with metadata from a database row
     *
     * @param resultSet   The database row
     * @param column      there in the row, to start taking values from
     * @param abstraction how to convert the row to a job
     * @throws SQLException in case of communication errors with the database
     */
    JobMetaData(ResultSet resultSet, int column) throws SQLException {
        consumer = resultSet.getString(column++);
        queued = resultSet.getTimestamp(column++);
        dequeueAfter = resultSet.getTimestamp(column++);
        tries = resultSet.getInt(column++) + 1;
    }

    /**
     * Fill into insert statement
     *
     * @param stmt        the statement what points out the columns listed in
     *                    {@link #COLUMNS}
     * @param startColumn position of first job column in the insert expression
     * @throws SQLException in case of sql type errors
     */
    void save(PreparedStatement stmt, int column) throws SQLException {
        stmt.setString(column++, consumer);
        stmt.setTimestamp(column++, queued);
        stmt.setTimestamp(column++, dequeueAfter);
        stmt.setInt(column++, tries);
    }

    /**
     * Fill into insert statement
     *
     * @param stmt        the statement what points out the columns listed in
     *                    {@link #COLUMNS}
     * @param startColumn position of first job column in the insert expression
     * @param postponed   how many milliseconds to postpone dequeue
     * @throws SQLException in case of sql type errors
     */
    void saveDelayed(PreparedStatement stmt, int column, long postponed) throws SQLException {
        stmt.setString(column++, consumer);
        stmt.setTimestamp(column++, queued);
        stmt.setLong(column++, postponed);
        stmt.setInt(column++, tries);
    }

    /**
     * The queue consumer that took the row
     * <p>
     * Only really needed for carrying the name through in case on a diag, and
     * the worker takes multiple queues
     *
     * @return String representation of consumer name
     */
    public String getConsumer() {
        return consumer;
    }

    /**
     * Then the job originally was queued
     *
     * @return timestamp
     */
    public Timestamp getQueued() {
        return queued;
    }

    /**
     * When the row was eligibly for dequeuing
     *
     * @return timestamp
     */
    public Timestamp getDequeueAfter() {
        return dequeueAfter;
    }

    /**
     * Number of times the row has been taken
     *
     * @return a positive number
     */
    public int getTries() {
        return tries;
    }

    @Override
    public String toString() {
        return "Job{" + "consumer=" + consumer + ", queued=" + queued + ", dequeueAfter=" + dequeueAfter + ", tries=" + tries + '}';
    }
}
