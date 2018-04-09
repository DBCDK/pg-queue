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
import dk.dbc.pgqueue.DeduplicateAbstraction;

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
     * @param tu      time unit
     */
    void awaitTermination(long timeout, TimeUnit tu);

    /**
     * Builder method for QueueWorker instance
     * @param <T> Job type
     */
    class Builder<T> {

        private static final Logger log = LoggerFactory.getLogger(Builder.class);

        private final QueueStorageAbstraction<T> storageAbstraction;
        private Integer maxTries;
        private Long emptyQueueSleep;
        private Long maxQueryTime;
        private Integer rescanEvery;
        private Integer idleRescanEvery;
        private List<String> consumerNames;
        private DataSource dataSource;
        private String databaseConnectThrottle;
        private String failureThrottle;
        private ExecutorService executor;
        private MetricRegistry metricRegistry;
        private DeduplicateAbstraction<T> deduplicateAbstraction;

        private Builder(QueueStorageAbstraction<T> storageAbstraction) {
            this.storageAbstraction = storageAbstraction;
            this.maxTries = null;
            this.emptyQueueSleep = null;
            this.maxQueryTime = null;
            this.rescanEvery = null;
            this.idleRescanEvery = null;
            this.consumerNames = null;
            this.dataSource = null;
            this.databaseConnectThrottle = null;
            this.failureThrottle = null;
            this.executor = null;
            this.metricRegistry = null;
            this.deduplicateAbstraction = null;
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
        public Builder<T> consume(String... names) {
            this.consumerNames = setOrFail(this.consumerNames, Arrays.asList(names), "queueNames");
            return this;
        }

        /**
         * Set the datasource (required)
         *
         * @param dataSource where to acquire database connections
         * @return self
         */
        public Builder<T> dataSource(DataSource dataSource) {
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
        public Builder<T> maxTries(int maxTries) {
            this.maxTries = setOrFail(this.maxTries, maxTries, "maxTries");
            return this;
        }

        /**
         * Set whether de duplication of jobs should occur
         *
         * @param deduplicateAbstraction definition of what a duplicate job is
         * @return self
         */
        public Builder<T> skipDuplicateJobs(DeduplicateAbstraction<T> deduplicateAbstraction) {
            this.deduplicateAbstraction = setOrFail(this.deduplicateAbstraction, deduplicateAbstraction, "skipDuplicateJobs");
            return this;
        }

        /**
         * How long to sleep for, when the queue is empty
         *
         * @param emptyQueueSleep number of milliseconds
         * @return self
         */
        public Builder<T> emptyQueueSleep(long emptyQueueSleep) {
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
        public Builder<T> maxQueryTime(long maxQueryTime) {
            this.maxQueryTime = setOrFail(this.maxQueryTime, maxQueryTime, "maxQueryTime");
            return this;
        }

        /**
         * How often to scan for earliest timestamp, in production with load
         *
         * @param rescanEvery every n queries rescan for earliest in queue
         * @return self
         */
        public Builder<T> rescanEvery(int rescanEvery) {
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
        public Builder<T> idleRescanEvery(int idleRescanEvery) {
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
        public Builder<T> databaseConnectThrottle(String databaseConnectThrottle) {
            this.databaseConnectThrottle = setOrFail(this.databaseConnectThrottle, databaseConnectThrottle, "databaseConnectThrottle");
            return this;
        }

        /**
         * Throttle string, for failures in job processing
         *
         * @param failureThrottle throttle spec
         * @return self
         */
        public Builder<T> failureThrottle(String failureThrottle) {
            this.failureThrottle = setOrFail(this.failureThrottle, failureThrottle, "failureThrottle");
            return this;
        }

        /**
         * Set the executor, that the processing should run in.
         * <p>
         * If none is set, a fixed threadpool with matching threads number to
         * consumer count
         *
         * @param executor the executor to run in
         * @return self
         */
        public Builder<T> executor(ExecutorService executor) {
            this.executor = setOrFail(this.executor, executor, "executor");
            return this;
        }

        /**
         * Set where to register performance stats
         *
         * @param metricRegistry the registry
         * @return self
         */
        public Builder<T> metricRegistry(MetricRegistry metricRegistry) {
            this.metricRegistry = setOrFail(this.metricRegistry, metricRegistry, "metricsRegistry");
            return this;
        }

        /**
         * Construct a QueueWorker
         *
         * @param <T>              Job type
         * @param n                number of consumers
         * @param consumerSupplier how to construct a consumer
         * @return queue worker
         */
        public <T> QueueWorker build(int n, Supplier<JobConsumer<T>> consumerSupplier) {
            Collection<JobConsumer<T>> consumers = new ArrayList<>();
            for (int i = 0 ; i < n ; i++) {
                consumers.add(consumerSupplier.get());
            }
            return build(consumers);
        }

        /**
         * Construct a QueueWorker
         *
         * @param <T>      Job type
         * @param n        number of consumers
         * @param consumer consumer instance
         * @return queue worker
         */
        public <T> QueueWorker build(int n, JobConsumer<T> consumer) {
            return build(n, () -> consumer);
        }

        /**
         * Construct a QueueWorker
         *
         * @param <T>       Job type
         * @param consumers consumer instances
         * @return queue worker
         */
        public <T> QueueWorker build(JobConsumer<T>... consumers) {
            return build(Arrays.asList(consumers));
        }

        /**
         * Construct a QueueWorker
         *
         * @param <T>       Job type
         * @param consumers consumer instances
         * @return queue worker
         */
        public <T> QueueWorker build(Collection<JobConsumer<T>> consumers) {
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
                                           deduplicateAbstraction,
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
     * @param <T>                Job type
     * @param storageAbstraction database/job converter
     * @return a builder
     */
    static <T> Builder<T> builder(QueueStorageAbstraction<T> storageAbstraction) {
        return new Builder(storageAbstraction);
    }
}
