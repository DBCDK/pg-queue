/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-ee-test
 *
 * dbc-pg-queue-ee-test is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-ee-test is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.ee.test;

import dk.dbc.pgqueue.QueueStorageAbstraction;
import dk.dbc.pgqueue.consumer.FatalQueueError;
import dk.dbc.pgqueue.consumer.JobConsumer;
import dk.dbc.pgqueue.consumer.JobMetaData;
import dk.dbc.pgqueue.consumer.NonFatalQueueError;
import dk.dbc.pgqueue.consumer.PostponedNonFatalQueueError;
import dk.dbc.pgqueue.ee.PgQueueEE;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Resource;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
@Singleton
@Startup
public class Consumer extends PgQueueEE<String> implements JobConsumer<String> {

    private static final Logger log = LoggerFactory.getLogger(Consumer.class);

    @Resource(lookup = "jdbc/consumer")
    DataSource dataSource;

    @Override
    public QueueStorageAbstraction<String> getStorageAbstraction() {
        return new QueueStorageAbstraction<String>() {
            String[] COLUMNS = "job".split(",");

            @Override
            public String[] columnList() {
                return COLUMNS;
            }

            @Override
            public String createJob(ResultSet resultSet, int startColumn) throws SQLException {
                return resultSet.getString(startColumn);
            }

            @Override
            public void saveJob(String job, PreparedStatement stmt, int startColumn) throws SQLException {
                stmt.setString(startColumn, job);
            }
        };
    }

    @Override
    public Collection<JobConsumer<String>> getJobConsumers() {
        return Arrays.asList((JobConsumer<String>) this);
    }

    @Override
    public String[] getQueueNames() {
        return "foo".split(",");
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    public void accept(Connection connection, String job, JobMetaData metaData) throws FatalQueueError, NonFatalQueueError, PostponedNonFatalQueueError {
        log.debug("metaData = {}; job = {}", metaData, job);
    }

    @Override
    public Logger getLogger() {
        return log;
    }

    @Override
    public long getQueueEmptySleep() {
        return 1_000L;
    }

}
