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
 * An exception that {@link JobConsumer} can throw, if the job should not be
 * retried
 * <p>
 * It has a boolean (defaults to true), to tell if this exception should cause
 * throttling
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class FatalQueueError extends Exception {

    private static final long serialVersionUID = 0xA0C8DD3D76A08C8BL;

    private final boolean throttle;

    public FatalQueueError() {
        this.throttle = true;
    }

    public FatalQueueError(String message) {
        super(message);
        this.throttle = true;
    }

    public FatalQueueError(String message, Throwable cause) {
        super(message, cause);
        this.throttle = true;
    }

    public FatalQueueError(Throwable cause) {
        super(cause);
        this.throttle = true;
    }

    public FatalQueueError(boolean throttle) {
        this.throttle = throttle;
    }

    public FatalQueueError(boolean throttle, String message) {
        super(message);
        this.throttle = throttle;
    }

    public FatalQueueError(boolean throttle, String message, Throwable cause) {
        super(message, cause);
        this.throttle = throttle;
    }

    public FatalQueueError(boolean throttle, Throwable cause) {
        super(cause);
        this.throttle = throttle;
    }

    public boolean shouldThrottle() {
        return throttle;
    }
}
