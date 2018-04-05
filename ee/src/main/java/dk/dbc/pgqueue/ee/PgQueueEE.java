/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-ee
 *
 * dbc-pg-queue-ee is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-ee is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.ee;

import com.codahale.metrics.MetricRegistry;
import dk.dbc.pgqueue.QueueStorageAbstraction;
import dk.dbc.pgqueue.consumer.JobConsumer;
import dk.dbc.pgqueue.consumer.QueueWorker;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dk.dbc.pgqueue.DeduplicateAbstraction;

/**
 * Helper class
 * <p>
 * Inherit from this, and implement abstract functions / override functions to
 * set up ad worker.
 * <p>
 * Remember:
 * <pre>
 *  * Inherited class should be annotated with:
 *    * @javax.ejb.Singleton;
 *    * @javax.ejb.Startup; * beans.xml should have "bean-discovery-mode" set to
 * "all"
 * </pre>
 *
 *
 * @author DBC {@literal <dbc.dk>}
 * @param <T> the job type
 */
public abstract class PgQueueEE<T> {

    private static final Logger LOG = LoggerFactory.getLogger(PgQueueEE.class);
    private QueueWorker worker;

    @PostConstruct
    public void init() {
        Logger log = getLogger();
        log.info("Starting up");

        this.worker = QueueWorker.builder(getStorageAbstraction())
                .skipDuplicateJobs(getDeduplicateAbstraction())
                .consume(getQueueNames())
                .dataSource(getDataSource())
                .emptyQueueSleep(getQueueEmptySleep())
                .failureThrottle(getFailureThrottle())
                .databaseConnectThrottle(getDatabaseConnectThrottle())
                .maxQueryTime(getMaxQueryTime())
                .rescanEvery(getRescanEvery())
                .idleRescanEvery(getIdleRescanEvery())
                .maxTries(getMaxTries())
                .metricRegistry(getMetricRegistry())
                .build(getJobConsumers());
        this.worker.start();
    }

    @PreDestroy
    public void destoy() {
        Logger log = getLogger();
        log.info("Shutting down");
        this.worker.stop();
        this.worker.awaitTermination(1, TimeUnit.MINUTES);
    }

    /**
     * Supply a logger
     *
     * @return a logger (for this class)
     */
    public Logger getLogger() {
        return LOG;
    }

    /**
     * Produce a storage abstraction
     *
     * @return storage abstraction
     */
    public abstract QueueStorageAbstraction<T> getStorageAbstraction();

    /**
     * Produce a storage abstraction
     *
     * @return storage abstraction
     */
    public abstract DeduplicateAbstraction<T> getDeduplicateAbstraction();

    /**
     * Produce a number of job consumers
     *
     * @return job consumers
     */
    public abstract Collection<JobConsumer<T>> getJobConsumers();

    /**
     * List of queue names to consume
     *
     * @return list of names
     */
    public abstract String[] getQueueNames();

    /**
     * Provide Database that has the queue
     *
     * @return datasource
     */
    public abstract DataSource getDataSource();

    /**
     * Provide failure throttle rules
     *
     * @return set of rules
     */
    public String getFailureThrottle() {
        return "";
    }

    /**
     * Provide database connect throttle rules
     *
     * @return set of rules
     */
    public String getDatabaseConnectThrottle() {
        return "";
    }

    /**
     * Provide number of milliseconds to sleep when the queue is empty
     *
     * @return default 10_000 (10s)
     */
    public long getQueueEmptySleep() {
        return 10_000L;
    }

    /**
     * Provide how long a query can take in milliseconds, before a new set of
     * prepared statements is made
     *
     * @return default 250ms
     */
    public long getMaxQueryTime() {
        return 250L;
    }

    /**
     * Provide a number for how often a rescan for 1st job in queue should be
     * performed
     *
     * @return 1000 (1 in 1000 times)
     */
    public int getRescanEvery() {
        return 1000;
    }

    /**
     * Provide a number for how often a rescan for first job in queue should be
     * done, when the queue is empty.
     * <p>
     * The {@link #getQueueEmptySleep() } value should be taken into account
     * when selecting this value, worst case before a job is found could be the
     * 2 multiplied
     *
     * @return every 5 times
     */
    public int getIdleRescanEvery() {
        return 5;
    }

    /**
     * Provide a number for how many tries a job should have before considered
     * fatally unprocessable.
     *
     * @return default 3
     */
    public int getMaxTries() {
        return 3;
    }

    /**
     * The metrics registry where to log stats
     *
     * @return null (unsupplied)
     */
    public MetricRegistry getMetricRegistry() {
        return null;
    }

}
