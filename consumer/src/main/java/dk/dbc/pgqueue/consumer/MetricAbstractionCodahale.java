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

import com.codahale.metrics.MetricRegistry;

/**
 * Implementation of metrics objects for codahale variant of metrics
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class MetricAbstractionCodahale implements MetricAbstraction {

    private final MetricRegistry metricRegistry;

    public MetricAbstractionCodahale(MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    @Override
    public Counter counter(Class clazz, String name) {
        com.codahale.metrics.Counter counter = metricRegistry.counter(MetricRegistry.name(clazz, name));
        return () -> {
            counter.inc();
        };
    }

    @Override
    public Timer timer(Class clazz, String name) {
        com.codahale.metrics.Timer timer = metricRegistry.timer(MetricRegistry.name(clazz, name));
        return () -> {
            return new Timer.Context() {
                com.codahale.metrics.Timer.Context context = timer.time();

                @Override
                public void close() {
                    context.close();
                }
            };
        };
    }

}
