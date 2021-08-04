/*
 * Copyright (C) 2021 DBC A/S (http://dbc.dk/)
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

module dbc.pgqueue.consumer {
    exports dk.dbc.pgqueue.consumer;

    requires transitive dbc.pgqueue.common;

    requires java.sql;
    requires org.flywaydb.core;
    requires org.postgresql.jdbc;

    requires microprofile.metrics.api;
    
    requires org.slf4j;
    requires logback.classic;
    requires logback.core;

    requires static com.github.spotbugs.annotations;

}
