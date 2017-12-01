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

import dk.dbc.pgqueue.QueueStorageAbstraction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Queue job container
 *
 * Wraps a queue job, and has metadata about it
 *
 * @author DBC {@literal <dbc.dk>}
 *
 */
public class JobMetaData {

    static final String COLUMNS = "ctid, consumer, queued, dequeueAfter, tries";
    static final int COLUMN_COUNT = 5;

    private final Object ctid;
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
        ctid = resultSet.getObject(column++);
        consumer = resultSet.getString(column++);
        queued = resultSet.getTimestamp(column++);
        dequeueAfter = resultSet.getTimestamp(column++);
        tries = resultSet.getInt(column++) + 1;
    }

    /**
     * The row id
     *
     * @return Object referring the selected row (only valid until 1st update of
     *         said row)
     */
    Object getCTID() {
        return ctid;
    }

    /**
     * The queue consumer that took the row
     *
     * Only really needed for carrying the name through in case on a diag, and
     * the worker takes multiple queues
     *
     * @return String representation of consumer name
     */
    String getConsumer() {
        return consumer;
    }

    /**
     * Then the job originally was queued
     *
     * @return timestamp
     */
    Timestamp getQueued() {
        return queued;
    }

    /**
     * When the row was eligibly for dequeuing
     *
     * @return timestamp
     */
    Timestamp getDequeueAfter() {
        return dequeueAfter;
    }

    /**
     * Number of times the row has been taken
     *
     * @return a number >= 1
     */
    int getTries() {
        return tries;
    }

    @Override
    public String toString() {
        return "Job{" + "ctid=" + ctid + ", consumer=" + consumer + ", queued=" + queued + ", dequeueAfter=" + dequeueAfter + ", tries=" + tries + '}';
    }
}
