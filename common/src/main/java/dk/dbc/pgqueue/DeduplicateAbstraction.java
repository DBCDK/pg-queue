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
package dk.dbc.pgqueue;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Class for collapsing multiple jobs into one
 *
 * @author DBC {@literal <dbc.dk>}
 *
 * @param <T> type of job
 */
public interface DeduplicateAbstraction<T> {

    /**
     * Return a (static) list of columns, in the order the other duplicate
     * values expect them.
     * <ul>
     * <li> Remember to create an index on the columns listed
     * <li> Null value columns are not supported
     * </ul>
     *
     * @return List of column names
     */
    String[] duplicateDeleteColumnList();

    /**
     * Fill in values to delete duplicate columns
     * <p>
     * Used only in dequeue context
     *
     * @param job         The job to be persisted
     * @param stmt        the statement what points out the columns listed in
     *                    {@link #columnList()}
     * @param startColumn position of first job column in the insert expression
     * @throws SQLException in case of errors with the statement
     */
    void duplicateValues(T job, PreparedStatement stmt, int startColumn) throws SQLException;

    /**
     * Merge the original job and the skipped job.
     * <p>
     * Useful if accumulation of a tracking id is needed
     *
     * @param originalJob The job that is chosen to be run
     * @param skippedJob The job chosen to be skipped
     * @return the new job that should be run (usually originalJob)
     */
    T mergeJob(T originalJob, T skippedJob);
}
