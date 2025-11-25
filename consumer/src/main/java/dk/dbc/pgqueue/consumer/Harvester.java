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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Implementation of {@link QueueWorker}, that
 *
 *
 * @author DBC {@literal <dbc.dk>}
 * @param <T> Job type
 */
public class Harvester<T> extends BasicHarvester<T> implements QueueWorker {
    private static final Logger log = LoggerFactory.getLogger(Harvester.class);
    private final List<JobWorker<T>> workers;


    public Harvester(Settings<T> config, DataSource dataSource, Collection<JobConsumer<T>> consumers) {
        super(config, dataSource);
        running = false;
        workers = consumers.stream()
                .map(c -> new JobWorker<>(c, this, config.health))
                .collect(Collectors.toList());
    }

    @Override
    public void start() {
        if (running) {
            throw new IllegalStateException("Consumer has already been started");
        }
        running = true;
        for (JobWorker<T> worker : workers) {
            settings.executor.execute(worker);
        }
    }

    @Override
    public void stop() {
        if (!running) {
            throw new IllegalStateException("Consumer is not running");
        }
        running = false;
        for (JobWorker<T> worker : workers) {
            worker.cancel();
        }
    }

    @Override
    public void awaitTermination(long timeout, TimeUnit tu) {
        if (running) {
            throw new IllegalStateException("Consumer is not stopped");
        }
        settings.executor.shutdown();
        try {
            boolean terminated = settings.executor.awaitTermination(timeout, tu);
            if (!terminated) {
                log.error("Error waiting for harvester-threads to finish: timed out");
            }
        } catch (InterruptedException ex) {
            log.error("Error waiting for harvester-threads to finish: {}", ex.getMessage());
            log.debug("Error waiting for harvester-threads to finish:", ex);
        }
    }

    @Override
    public List<String> hungThreads() {
        return settings.health.hungThreads();
    }

}
