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

import java.sql.Connection;

/**
 * Interface representing an actual processing of a job
 *
 * @author DBC {@literal <dbc.dk>}
 *
 * @param <T> the type of the job
 */
public interface JobConsumer<T> {

    /**
     * Process a job
     *
     * @param connection the database connection in which the job is taken -
     *                   this is separated via a savepoint
     * @param job        The actual job to process
     * @param metaData   The metadata from the queue
     * @throws FatalQueueError             If the job should not be retried
     * @throws NonFatalQueueError          If the job should be retried
     *                                     immediately
     * @throws PostponedNonFatalQueueError If the job should be retried after a
     *                                     given grace period
     */
    void accept(Connection connection, T job, JobMetaData metaData) throws FatalQueueError,
                                                                           NonFatalQueueError,
                                                                           PostponedNonFatalQueueError;

}
