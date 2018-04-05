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

import dk.dbc.pgqueue.QueueStorageAbstractionDequeue;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Queue job container
 *
 * Wraps a queue job, and has metadata about it
 *
 * @author DBC {@literal <dbc.dk>}
 *
 * @param <T>
 */
class JobWithMetaData<T> extends JobMetaData {

    private final T job;

    /**
     * Construct a job with metadata from a database row
     *
     * @param resultSet   The database row
     * @param column      there in the row, to start taking values from
     * @param abstraction how to convert the row to a job
     * @throws SQLException in case of communication errors with the database
     */
    JobWithMetaData(ResultSet resultSet, int column, QueueStorageAbstractionDequeue<T> abstraction) throws SQLException {
        super(resultSet, column);
        job = abstraction.createJob(resultSet, column + JobMetaData.COLUMN_COUNT);
    }

    /**
     * Get the actual job
     *
     * @return job of type T
     */
    T getActualJob() {
        return job;
    }

}
