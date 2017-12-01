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

import com.codahale.metrics.MetricRegistry;
import dk.dbc.pgqueue.QueueStorageAbstraction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public interface QueueWorker {

    /**
     * Start a queue worker
     */
    void start();

    /**
     * Stop a queue worker
     */
    void stop();

    /**
     * @see ExecutorService#awaitTermination(long,
     * java.util.concurrent.TimeUnit)
     *
     * @param timeout how many milliseconds to wait for termination
     */
    void awaitTermination(long timeout, TimeUnit tu);

    /**
     * Builder method for QueueWorker instance
     */
    class Builder {

        private static final Logger log = LoggerFactory.getLogger(Builder.class);

        private Integer maxTries = null;
        private Long emptyQueueSleep = null;
        private Long maxQueryTime = null;
        private Integer rescanEvery = null;
        private Integer idleRescanEvery = null;
        private List<String> consumerNames = null;
        private DataSource dataSource = null;
        private String databaseConnectThrottle = null;
        private String failureThrottle = null;
        private ExecutorService executor = null;
        private MetricRegistry metricRegistry = null;

        private Builder() {
        }

        /**
         * Set which queues to consume from (required)
         * <p>
         * Consumation is done in order ie. drain queue1 first then queue2, if
         * queue1 acquires new rows take them before continuing on queue2
         *
         * @param names list of consumer names
         * @return self
         */
        public Builder consume(String... names) {
            this.consumerNames = setOrFail(this.consumerNames, Arrays.asList(names), "queueNames");
            return this;
        }

        /**
         * Set the datasource (required)
         *
         * @param dataSource where to acquire database connections
         * @return self
         */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = setOrFail(this.dataSource, dataSource, "dataSource");
            return this;
        }

        /**
         * Set number of tries, before failing a job
         *
         * @param maxTries how persistent to try for successful processing of
         *                 job
         * @return self
         */
        public Builder maxTries(int maxTries) {
            this.maxTries = setOrFail(this.maxTries, maxTries, "maxTries");
            return this;
        }

        /**
         * How long to sleep for, when the queue is empty
         *
         * @param emptyQueueSleep number of milliseconds
         * @return self
         */
        public Builder emptyQueueSleep(long emptyQueueSleep) {
            this.emptyQueueSleep = setOrFail(this.emptyQueueSleep, emptyQueueSleep, "emptyQueueSleep");
            return this;
        }

        /**
         * How long a query (rescan queue for earliest timestamp) is allowed to
         * take, before replanning prepared statements
         *
         * @param maxQueryTime number of milliseconds
         * @return self
         */
        public Builder maxQueryTime(long maxQueryTime) {
            this.maxQueryTime = setOrFail(this.maxQueryTime, maxQueryTime, "maxQueryTime");
            return this;
        }

        /**
         * How often to scan for earliest timestamp, in production with load
         *
         * @param rescanEvery every n queries rescan for earliest in queue
         * @return self
         */
        public Builder rescanEvery(int rescanEvery) {
            this.rescanEvery = setOrFail(this.rescanEvery, rescanEvery, "rescanEvery");
            return this;
        }

        /**
         * How often to rescan for earliest timestamp, when queue is empty.
         * <p>
         * Setting this to 1, could lead to high database load
         *
         * @param idleRescanEvery for every n idle sleeps rescan for earliest
         *                        timestamp
         * @return self
         */
        public Builder idleRescanEvery(int idleRescanEvery) {
            this.idleRescanEvery = setOrFail(this.idleRescanEvery, this.idleRescanEvery, "rescanEvery");
            return this;
        }

        /**
         * Throttle string, for database connects (in case of connection
         * failure)
         *
         * @param databaseConnectThrottle throttle spec
         * @return self
         */
        public Builder databaseConnectThrottle(String databaseConnectThrottle) {
            this.databaseConnectThrottle = setOrFail(this.databaseConnectThrottle, databaseConnectThrottle, "databaseConnectThrottle");
            return this;
        }

        /**
         * Throttle string, for failures in job processing
         *
         * @param failureThrottle throttle spec
         * @return self
         */
        public Builder failureThrottle(String failureThrottle) {
            this.failureThrottle = setOrFail(this.failureThrottle, failureThrottle, "failureThrottle");
            return this;
        }

        /**
         * Set the executor, that the processing should run in.
         * <p>
         * If none is set, a fixed threadpool with one
         *
         * @param executor
         * @return
         */
        public Builder executor(ExecutorService executor) {
            this.executor = setOrFail(this.executor, executor, "executor");
            return this;
        }

        /**
         * Set where to register performance stats
         *
         * @param metricRegistry the registry
         * @return self
         */
        public Builder metricRegistry(MetricRegistry metricRegistry) {
            this.metricRegistry = setOrFail(this.metricRegistry, metricRegistry, "executor");
            return this;
        }

        /**
         * Construct a QueueWorker
         *
         * @param <T>                Job type
         * @param storageAbstraction database/job converter
         * @param n                  number of consumers
         * @param consumerSupplier   how to construct a consumer
         * @return queue worker
         */
        public <T> QueueWorker build(QueueStorageAbstraction<T> storageAbstraction, int n, Supplier<JobConsumer<T>> consumerSupplier) {
            Collection<JobConsumer<T>> consumers = new ArrayList<>();
            for (int i = 0 ; i < n ; i++) {
                consumers.add(consumerSupplier.get());
            }
            return build(storageAbstraction, consumers);
        }

        /**
         * Construct a QueueWorker
         *
         * @param <T>                Job type
         * @param storageAbstraction database/job converter
         * @param n                  number of consumers
         * @param consumer           consumer instance
         * @return queue worker
         */
        public <T> QueueWorker build(QueueStorageAbstraction<T> storageAbstraction, int n, JobConsumer<T> consumer) {
            return build(storageAbstraction, n, () -> consumer);
        }

        /**
         * Construct a QueueWorker
         *
         * @param <T>                Job type
         * @param storageAbstraction database/job converter
         * @param consumers           consumer instances
         * @return queue worker
         */
        public <T> QueueWorker build(QueueStorageAbstraction<T> storageAbstraction, JobConsumer<T>... consumers) {
            return build(storageAbstraction, Arrays.asList(consumers));
        }

        /**
         * Construct a QueueWorker
         *
         * @param <T>                Job type
         * @param storageAbstraction database/job converter
         * @param consumers           consumer instances
         * @return queue worker
         */
        public <T> QueueWorker build(QueueStorageAbstraction<T> storageAbstraction, Collection<JobConsumer<T>> consumers) {
            if (executor == null) {
                executor = Executors.newFixedThreadPool(consumers.size());
            }
            if (metricRegistry == null) {
                log.warn("unset metricRegistry");
            }
            if (consumers.isEmpty()) {
                throw new IllegalArgumentException("No consumer is supplied");
            }
            Settings config = new Settings(required(consumerNames, "queueNames should be set"),
                                           storageAbstraction,
                                           or(maxTries, 3),
                                           or(emptyQueueSleep, 10_000L),
                                           or(maxQueryTime, 50L),
                                           or(rescanEvery, 100),
                                           or(idleRescanEvery, 10),
                                           new Throttle(or(databaseConnectThrottle, "")),
                                           new Throttle(or(failureThrottle, "")),
                                           executor,
                                           or(metricRegistry, new MetricRegistry()));
            return new Harvester(config, dataSource, consumers);
        }

        private static <T> T setOrFail(T field, T value, String fieldName) {
            if (field != null) {
                throw new IllegalArgumentException(fieldName + " has already been set");
            }
            return value;
        }

        private static <T> T or(T... ts) {
            for (T t : ts) {
                if (t != null) {
                    return t;
                }
            }
            throw new IllegalStateException("No Default value set");
        }

        private static <T> T required(T t, String message) {
            if (t != null) {
                return t;
            }
            throw new IllegalArgumentException(message);
        }

    }

    /**
     * Construct a builder
     *
     * @return a builder
     */
    static Builder builder() {
        return new Builder();
    }
}
