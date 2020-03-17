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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
     * Accessor for {@link QueueHealth#hungThreads()}
     *
     * @return list of threads that hangs in a database connection
     */
    List<String> hungThreads();

    /**
     * Builder method for QueueWorker instance
     *
     * @param <T> Job type
     */
    class Builder<T> {

        private static final Logger log = LoggerFactory.getLogger(Builder.class);

        public static final String ENV_MAX_TRIES = "MAX_TRIES";
        public static final String ENV_QUEUE_WINDOW = "QUEUE_WINDOW";
        public static final String ENV_EMPTY_QUEUE_SLEEP = "EMPTY_QUEUE_SLEEP";
        public static final String ENV_MAX_QUERY_TIME = "MAX_QUERY_TIME";
        public static final String ENV_RESCAN_EVERY = "RESCAN_EVERY";
        public static final String ENV_IDLE_RESCAN_EVERY = "IDLE_RESCAN_EVERY";
        public static final String ENV_DATABASE_THROTTLE = "DATABASE_THROTTLE";
        public static final String ENV_DEDUPLICATE_DISABLE = "DEDUPLICATE_DISABLE";
        public static final String ENV_FAILURE_THROTTLE = "FAILURE_THROTTLE";
        public static final String ENV_QUEUES = "QUEUES";

        private static final Map<String, String> DEFAULT_ENVIRONMENT =
                Arrays.asList(ENV_MAX_TRIES + "=3",
                              ENV_QUEUE_WINDOW + "=500ms",
                              ENV_EMPTY_QUEUE_SLEEP + "=10s",
                              ENV_MAX_QUERY_TIME + "=250ms",
                              ENV_RESCAN_EVERY + "=500",
                              ENV_IDLE_RESCAN_EVERY + "=5",
                              ENV_DATABASE_THROTTLE + "=1/5s,3/m,5/10m",
                              ENV_FAILURE_THROTTLE + "=2/100ms,3/s,5/m",
                              ENV_DEDUPLICATE_DISABLE + "=100ms/5m")
                        .stream()
                        .map(s -> s.split("=", 2))
                        .collect(Collectors.toMap(s -> s[0], s -> s[1]));

        private final QueueStorageAbstraction<T> storageAbstraction;
        private Integer maxTries;
        private Long window;
        private Long emptyQueueSleep;
        private Long maxQueryTime;
        private Integer rescanEvery;
        private Integer idleRescanEvery;
        private List<String> consumerNames;
        private DataSource dataSource;
        private String databaseConnectThrottle;
        private DeduplicateDisable deduplicateDisable;
        private String failureThrottle;
        private ExecutorService executor;
        private MetricAbstraction metricsAbstraction;
        private DeduplicateAbstraction<T> deduplicateAbstraction;
        private Boolean includePostponedInDeduplication;
        private QueueHealth health;

        private Builder(QueueStorageAbstraction<T> storageAbstraction) {
            this.storageAbstraction = storageAbstraction;
            this.maxTries = null;
            this.window = null;
            this.emptyQueueSleep = null;
            this.maxQueryTime = null;
            this.rescanEvery = null;
            this.idleRescanEvery = null;
            this.consumerNames = null;
            this.dataSource = null;
            this.databaseConnectThrottle = null;
            this.failureThrottle = null;
            this.executor = null;
            this.metricsAbstraction = null;
            this.deduplicateAbstraction = null;
            this.includePostponedInDeduplication = null;
            this.health = null;
        }

        /**
         * Set which queues to consume from (required)
         * <p>
         * Consummation is done in order ie. drain queue1 first then queue2, if
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
         * @param maxTries how persistently to try for successful processing of job
         * @return self
         */
        public Builder<T> maxTries(int maxTries) {
            this.maxTries = setOrFail(this.maxTries, maxTries, "maxTries");
            return this;
        }

        /**
         * Set window in ms for uncommitted transactions
         *
         * @param window how many ms are looked back in time
         * @return self
         */
        public Builder<T> window(long window) {
            this.window = setOrFail(this.window, window, "window");
            return this;
        }

        /**
         * Set whether deduplication of jobs should occur
         *
         * @param deduplicateAbstraction definition of what a duplicate job is
         * @return self
         */
        public Builder<T> skipDuplicateJobs(DeduplicateAbstraction<T> deduplicateAbstraction) {
            this.deduplicateAbstraction = setOrFail(this.deduplicateAbstraction, deduplicateAbstraction, "skipDuplicateJobs");
            this.includePostponedInDeduplication = setOrFail(this.includePostponedInDeduplication, true, "includePostponedInDeduplication");
            return this;
        }

        public Builder<T> skipDuplicateJobs(DeduplicateAbstraction<T> deduplicateAbstraction, boolean includePostponedInDeduplication) {
            this.deduplicateAbstraction = setOrFail(this.deduplicateAbstraction, deduplicateAbstraction, "skipDuplicateJobs");
            this.includePostponedInDeduplication = setOrFail(this.includePostponedInDeduplication, includePostponedInDeduplication, "includePostponedInDeduplication");
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
         * take, before re-planning prepared statements
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
         * Setting this to 1 could lead to high database load
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
         * Throttle string, for database connects (in case of connection failure)
         *
         * @param databaseConnectThrottle throttle spec
         * @return self
         */
        public Builder<T> databaseConnectThrottle(String databaseConnectThrottle) {
            this.databaseConnectThrottle = setOrFail(this.databaseConnectThrottle, databaseConnectThrottle, "databaseConnectThrottle");
            return this;
        }

        /**
         * Sets a limit/period (both timespecs) if deduplicate takes more than
         * limit it is disabled for period
         *
         * @param spec deduplicateDisable spec ({duration}/{duraion})
         * @return self
         */
        public Builder<T> deduplicateDisable(String spec) {
            this.deduplicateDisable = setOrFail(this.deduplicateDisable, deduplicateDisableBuild(spec), "deduplicateDisable");
            return this;
        }

        private DeduplicateDisable deduplicateDisableBuild(String spec) throws IllegalArgumentException {
            String[] parts = spec.split("/", 2);
            if (parts.length != 2)
                throw new IllegalArgumentException("deduplicate disable spec: " + spec);
            return new DeduplicateDisable(milliseconds(parts[0]), milliseconds(parts[1]));
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
         * Set missing values from environment
         * <p>
         * You cannot set values after this, they might fail at runtime with
         * value has already been set
         *
         * @return self
         */
        public Builder<T> fromEnv() {
            return fromEnv(System.getenv());
        }

        /**
         * Set missing values from environment using default values
         * <p>
         * You cannot set values after this, they might fail at runtime with
         * value has already been set
         *
         * @return self
         */
        public Builder<T> fromEnvWithDefaults() {
            return fromEnvWithDefaults(DEFAULT_ENVIRONMENT);
        }

        /**
         * Set missing values from environment using supplied default values
         * <p>
         * You cannot set values after this, they might fail at runtime with
         * value has already been set
         *
         * @param defaultValues map of fallback values
         * @return self
         */
        public Builder<T> fromEnvWithDefaults(Map<String, String> defaultValues) {
            Map<String, String> env = new HashMap<>(defaultValues);
            env.putAll(System.getenv());
            return fromEnv(env);
        }

        /**
         * Set missing values from a map
         *
         * @param env the environment to take values from
         * @return self
         */
        private Builder<T> fromEnv(Map<String, String> env) {
            String s;
            if (maxTries == null && ( s = env.get(ENV_MAX_TRIES) ) != null) {
                maxTries = Integer.max(1, Integer.parseInt(s));
            }
            if (window == null && ( s = env.get(ENV_QUEUE_WINDOW) ) != null) {
                window = milliseconds(s);
            }
            if (emptyQueueSleep == null && ( s = env.get(ENV_EMPTY_QUEUE_SLEEP) ) != null) {
                emptyQueueSleep = milliseconds(s);
            }
            if (maxQueryTime == null && ( s = env.get(ENV_MAX_QUERY_TIME) ) != null) {
                maxQueryTime = milliseconds(s);
            }
            if (rescanEvery == null && ( s = env.get(ENV_RESCAN_EVERY) ) != null) {
                rescanEvery = Integer.max(1, Integer.parseInt(s));
            }
            if (idleRescanEvery == null && ( s = env.get(ENV_IDLE_RESCAN_EVERY) ) != null) {
                idleRescanEvery = Integer.max(1, Integer.parseInt(s));
            }
            if (failureThrottle == null && ( s = env.get(ENV_FAILURE_THROTTLE) ) != null) {
                failureThrottle = s;
            }
            if (databaseConnectThrottle == null && ( s = env.get(ENV_DATABASE_THROTTLE) ) != null) {
                databaseConnectThrottle = s;
            }
            if (consumerNames == null && ( s = env.get(ENV_QUEUES) ) != null) {
                consumerNames = Arrays.stream(s.split("[,\\s]+"))
                        .filter(queue -> !queue.isEmpty())
                        .collect(Collectors.toList());
            }
            if (deduplicateDisable == null && ( s = env.get(ENV_DEDUPLICATE_DISABLE) ) != null) {
                deduplicateDisable = deduplicateDisableBuild(s);
            }
            return this;
        }

        /**
         * Set the executor the processing should run in.
         * <p>
         * If none is set, a fixed thread pool with matching threads number to
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
         * Set the health instance the database should report in.
         *
         * @param health the health instance
         * @return self
         */
        public Builder<T> health(QueueHealth health) {
            this.health = setOrFail(this.health, health, "health");
            return this;
        }

        /**
         * Set where to register performance stats
         * <p>
         * use either
         * {@link #metricRegistryCodahale(com.codahale.metrics.MetricRegistry)}
         * or
         * {@link #metricRegistryMicroProfile(org.eclipse.microprofile.metrics.MetricRegistry)}.
         *
         * @param metricRegistry the registry
         * @return self
         */
        @Deprecated
        public Builder<T> metricRegistry(com.codahale.metrics.MetricRegistry metricRegistry) {
            this.metricsAbstraction = setOrFail(this.metricsAbstraction, new MetricAbstractionCodahale(metricRegistry), "metricsRegistry(Codahale/MicroProfile)");
            log.warn("Deprecated use: {}.metricsRegistry(...)", getClass().getCanonicalName());
            return this;
        }

        /**
         * Set where to register performance stats (codahale style metrics)
         *
         * @param metricRegistry the registry (or null)
         * @return self
         */
        public Builder<T> metricRegistryCodahale(com.codahale.metrics.MetricRegistry metricRegistry) {
            if (metricRegistry != null)
                this.metricsAbstraction = setOrFail(this.metricsAbstraction, new MetricAbstractionCodahale(metricRegistry), "metricsRegistry(Codahale)");
            return this;
        }

        /**
         * Set where to register performance stats (eclipse microprofile style metrics)
         *
         * @param metricRegistry the registry (or null)
         * @return self
         */
        public Builder<T> metricRegistryMicroProfile(org.eclipse.microprofile.metrics.MetricRegistry metricRegistry) {
            if (metricRegistry != null)
                this.metricsAbstraction = setOrFail(this.metricsAbstraction, new MetricAbstractionMicroProfile(metricRegistry), "metricsRegistry(MicroProfile)");
            return this;
        }

        /**
         * Construct a QueueWorker
         *
         * @param n                number of consumers
         * @param consumerSupplier how to construct a consumer
         * @return queue worker
         */
        public QueueWorker build(int n, Supplier<JobConsumer<T>> consumerSupplier) {
            Collection<JobConsumer<T>> consumers = new ArrayList<>();
            for (int i = 0 ; i < n ; i++) {
                consumers.add(consumerSupplier.get());
            }
            return build(consumers);
        }

        /**
         * Construct a QueueWorker
         *
         * @param n        number of consumers
         * @param consumer consumer instance
         * @return queue worker
         */
        public QueueWorker build(int n, JobConsumer<T> consumer) {
            return build(n, () -> consumer);
        }

        /**
         * Construct a QueueWorker
         *
         * @param consumers consumer instances
         * @return queue worker
         */
        public QueueWorker build(JobConsumer<T>... consumers) {
            return build(Arrays.asList(consumers));
        }

        /**
         * Construct a QueueWorker
         *
         * @param consumers consumer instances
         * @return queue worker
         */
        public QueueWorker build(Collection<JobConsumer<T>> consumers) {
            if (executor == null) {
                executor = Executors.newFixedThreadPool(consumers.size());
            }
            if (health == null) {
                health = new QueueHealth();
            }
            if (metricsAbstraction == null) {
                log.warn("unset metricRegistry");
            }
            if (consumers.isEmpty()) {
                throw new IllegalArgumentException("No consumer is supplied");
            }
            Settings config = new Settings(required(consumerNames, "queueNames should be set"),
                                           storageAbstraction,
                                           deduplicateAbstraction,
                                           includePostponedInDeduplication,
                                           or(maxTries, 3),
                                           or(emptyQueueSleep, 10_000L),
                                           or(maxQueryTime, 50L),
                                           or(rescanEvery, 100),
                                           or(idleRescanEvery, 10),
                                           new Throttle(or(databaseConnectThrottle, "")),
                                           new Throttle(or(failureThrottle, "")),
                                           executor,
                                           or(metricsAbstraction, new MetricAbstractionNull()),
                                           or(window, 100L),
                                           health,
                                           or(deduplicateDisable, new DeduplicateDisable()));
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

        static long milliseconds(String spec) {
            String[] split = spec.split("(?<=\\d)(?=\\D)");
            if (split.length == 2) {
                long units = Long.parseUnsignedLong(split[0], 10);
                switch (split[1].toLowerCase(Locale.ROOT).trim()) {
                    case "ms":
                        return TimeUnit.MILLISECONDS.toMillis(units);
                    case "s":
                        return TimeUnit.SECONDS.toMillis(units);
                    case "m":
                        return TimeUnit.MINUTES.toMillis(units);
                    case "h":
                        return TimeUnit.HOURS.toMillis(units);
                    default:
                        break;
                }
            }
            throw new IllegalArgumentException("Invalid time spec: " + spec);
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
