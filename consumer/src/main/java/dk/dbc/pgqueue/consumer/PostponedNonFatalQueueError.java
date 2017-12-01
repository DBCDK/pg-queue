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

/**
 * An exception {@link JobConsumer} can throw if a job should be retried in a
 * while.
 *
 * It goes without saying that, queue order cannot be guaranteed if this is
 * thrown
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class PostponedNonFatalQueueError extends NonFatalQueueError {

    private static final long serialVersionUID = 2390458654559281815L;

    private final long postponedMs;

    /**
     * In how many milliseconds this sould be available for dequeuing again
     *
     * @param ms number of milliseconds
     */
    public PostponedNonFatalQueueError(long ms) {
        this.postponedMs = ms;
    }

    public PostponedNonFatalQueueError(String message, long ms) {
        super(message);
        this.postponedMs = ms;
    }

    public PostponedNonFatalQueueError(String message, long ms, Throwable cause) {
        super(message, cause);
        this.postponedMs = ms;
    }

    public PostponedNonFatalQueueError(long ms, Throwable cause) {
        super(cause);
        this.postponedMs = ms;
    }

    public long getPostponedMs() {
        return postponedMs;
    }
}
