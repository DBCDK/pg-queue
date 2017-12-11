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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * This is a storage class for configuration, shared among harvester and
 * JobWorker
 *
 * @author DBC {@literal <dbc.dk>}
 * @param <T>
 */
class Settings<T> {

    final List<String> consumerNames;
    final QueueStorageAbstraction<T> storageAbstraction;
    final int maxTries;
    final long emptyQueueSleep;
    final long maxQueryTime;
    final int fullScanEvery;
    final int idleFullScanEvery;
    final Throttle databaseConnectThrottle;
    final Throttle failureThrottle;
    final ExecutorService executor;
    final MetricRegistry metricRegistry;

    Settings(List<String> consumerNames, QueueStorageAbstraction<T> storageAbstraction, int maxTries, long emptyQueueSleep, long maxQueryTime, int fullScanEvery, int idleFullScanEvery, Throttle databaseConnectThrottle, Throttle failureThrottle, ExecutorService executor, MetricRegistry metricRegistry) {
        this.maxTries = maxTries;
        this.emptyQueueSleep = emptyQueueSleep;
        this.maxQueryTime = maxQueryTime;
        this.consumerNames = Collections.unmodifiableList(consumerNames);
        this.storageAbstraction = storageAbstraction;
        this.databaseConnectThrottle = databaseConnectThrottle;
        this.failureThrottle = failureThrottle;
        this.fullScanEvery = fullScanEvery;
        this.executor = executor;
        this.metricRegistry = metricRegistry;
        this.idleFullScanEvery = idleFullScanEvery;
    }
}
