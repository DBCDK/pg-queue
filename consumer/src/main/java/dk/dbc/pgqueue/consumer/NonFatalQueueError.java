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
 * An exception that {@link JobConsumer} can throw if a job has failed, but
 * should be retried immediately
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class NonFatalQueueError extends Exception {

    private static final long serialVersionUID = 2779580015877060470L;

    public NonFatalQueueError() {
    }

    public NonFatalQueueError(String message) {
        super(message);
    }

    public NonFatalQueueError(String message, Throwable cause) {
        super(message, cause);
    }

    public NonFatalQueueError(Throwable cause) {
        super(cause);
    }
}
