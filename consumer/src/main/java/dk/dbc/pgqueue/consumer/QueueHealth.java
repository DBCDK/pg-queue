/*
 * Copyright (C) 2018 DBC A/S (http://dbc.dk/)
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class QueueHealth {

    private final int l;
    private final ChronoUnit tu;
    private final ConcurrentHashMap<Thread, Instant> map;

    interface Context extends AutoCloseable {

        @Override
        void close();
    }

    public QueueHealth() {
        this(1, ChronoUnit.MINUTES);
    }

    public QueueHealth(int l, ChronoUnit tu) {
        this.l = l;
        this.tu = tu;
        this.map = new ConcurrentHashMap<>();
    }

    public List<String> hungThreads() {
        return hungThreads(l, tu);
    }

    public List<String> hungThreads(int l, ChronoUnit tu) {
        Instant cutoff = Instant.now().minus(l, tu);
        return map.entrySet().stream()
                .filter(e -> e.getValue().isBefore(cutoff))
                .map(e -> e.getKey().getName())
                .collect(Collectors.toList());
    }

    Context databaseCall() {
        Thread thread = Thread.currentThread();
        map.put(thread, Instant.now());
        return new Context() {
            @Override
            public void close() {
                map.remove(thread);
            }
        };
    }

}
