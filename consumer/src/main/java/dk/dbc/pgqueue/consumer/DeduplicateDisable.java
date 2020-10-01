/*
 * Copyright (C) 2019 DBC A/S (http://dbc.dk/)
 *
 * This is part of pg-queue-consumer
 *
 * pg-queue-consumer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * pg-queue-consumer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.consumer;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
class DeduplicateDisable {

    private final long limit;
    private final long disablePeriod;
    private long disableUntil;

    public DeduplicateDisable() {
        limit = Long.MAX_VALUE;
        disablePeriod = 0;
        disableUntil = 0;
    }

    public DeduplicateDisable(long limit, long disablePeriod) {
        this.limit = limit;
        this.disablePeriod = disablePeriod;
        disableUntil = 0;
    }

    synchronized boolean canDeduplicate() {
        return time() > disableUntil;
    }

    synchronized private void registerDuration(long ms) {
        long now = time();
        long duration = now - ms;
        if (duration > limit)
            disableUntil = now + disablePeriod;
    }

    long time() {
        return System.currentTimeMillis();
    }

    Context context() {
        long start = time();

        return () -> {
            registerDuration(start);
        };
    }

    interface Context extends AutoCloseable {

        @Override
        void close();
    }
}
