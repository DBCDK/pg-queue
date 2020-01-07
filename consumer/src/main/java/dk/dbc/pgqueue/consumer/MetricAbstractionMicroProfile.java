/*
 * Copyright (C) 2020 DBC A/S (http://dbc.dk/)
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

import org.eclipse.microprofile.metrics.MetricRegistry;

public class MetricAbstractionMicroProfile implements MetricAbstraction {

    private final MetricRegistry metricRegistry;

    public MetricAbstractionMicroProfile(MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    @Override
    public Counter counter(Class clazz, String name) {
        org.eclipse.microprofile.metrics.Counter counter = metricRegistry.counter(com.codahale.metrics.MetricRegistry.name(clazz, name));
        return () -> {
            counter.inc();
        };
    }

    @Override
    public Timer timer(Class clazz, String name) {
        org.eclipse.microprofile.metrics.Timer timer = metricRegistry.timer(com.codahale.metrics.MetricRegistry.name(clazz, name));
        return () -> {
            return (Timer.Context) timer.time();
        };
    }
}
