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

/**
 * Producer of timings objects
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public interface MetricAbstraction {

    interface Timer {

        /**
         * Try with resources context that stops a timer
         */
        interface Context extends AutoCloseable {

            @Override
            void close();

        }

        /**
         * Start timing
         *
         * @return context in which {@link Context#close()} stops the timer
         */
        Context time();

    }

    interface Counter {

        void inc();
    }

    /**
     * Create a metrics counter for a class with a given name
     *
     * @param clazz class that contains the counter
     * @param name  name of the counter
     * @return counter object
     */
    Counter counter(Class clazz, String name);

    /**
     * Create a metrics timer for a class with a given name
     *
     * @param clazz class that contains the timer
     * @param name  name of the timer
     * @return timer object
     */
    Timer timer(Class clazz, String name);
}
