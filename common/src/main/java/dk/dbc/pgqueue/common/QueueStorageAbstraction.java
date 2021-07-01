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
package dk.dbc.pgqueue.common;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Class made for mapping a job structure to and from a database table
 *
 * @author DBC {@literal <dbc.dk>}
 *
 * @param <T> type of job
 */
public interface QueueStorageAbstraction<T> {

    /**
     * Return a (static) list of columns, in the order the other methods expect
     * them (createJob and saveJob).
     *
     * @return list of column names
     */
    String[] columnList();

    /**
     * Convert a result set, that includes the columns listed in
     * {@link #columnList()} starting a a give position.
     * <p>
     * Only used in a dequeue context
     *
     * @param resultSet   data from the database
     * @param startColumn position of first job column in the dataset
     * @return new object of job type
     * @throws SQLException in case of communication errors
     */
    T createJob(ResultSet resultSet, int startColumn) throws SQLException;

    /**
     * Save a job to a database table
     * <p>
     * Used both in enqueue and dequeue (queue_error) context
     *
     * @param job         The job to be persisted
     * @param stmt        the statement what points out the columns listed in
     *                    {@link #columnList()}
     * @param startColumn position of first job column in the insert expression
     * @throws SQLException in case of errors with the statement
     */
    void saveJob(T job, PreparedStatement stmt, int startColumn) throws SQLException;

}
