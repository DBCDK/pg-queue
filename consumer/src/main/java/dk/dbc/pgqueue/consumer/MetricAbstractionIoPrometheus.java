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

import io.prometheus.metrics.core.metrics.Summary;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

/**
 * Implementation of metrics objects for io.prometheus variant of metrics
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class MetricAbstractionIoPrometheus implements MetricAbstraction {

    private final PrometheusRegistry registry;

    public MetricAbstractionIoPrometheus(PrometheusRegistry registry) {
        this.registry = registry;
    }

    public MetricAbstractionIoPrometheus() {
        this(PrometheusRegistry.defaultRegistry);
    }

    @Override
    public Counter counter(Class clazz, String name) {
        return io.prometheus.metrics.core.metrics.Counter.builder()
                .name(name(clazz, name))
                .register(registry)::inc;
    }

    @Override
    public Timer timer(Class clazz, String name) {
        Summary summary = Summary.builder()
                .name(name(clazz, name))
                .quantile(.5, 0.1)
                .quantile(.75, 0.1)
                .quantile(.95, 0.1)
                .quantile(.98, 0.1)
                .quantile(.99, 0.1)
                .register(registry);

        return () -> {
            return new Timer.Context() {
                io.prometheus.metrics.core.datapoints.Timer context = summary.startTimer();

                @Override
                public void close() {
                    context.close();
                }
            };
        };
    }

    private String name(Class clazz, String name) {
        return clazz.getCanonicalName() + "." + name;
    }
}
