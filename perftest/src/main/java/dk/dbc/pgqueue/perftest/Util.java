/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-perftest
 *
 * dbc-pg-queue-perftest is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-perftest is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.perftest;

import dk.dbc.pgqueue.DatabaseMigrator;
import dk.dbc.pgqueue.QueueStorageAbstraction;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.commons.dbcp2.BasicDataSource;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class Util {

    public static DataSource database(String url) throws URISyntaxException {
        URI dbUri = new URI("//" + url);
        int port = dbUri.getPort();
        if (port == -1) {
            port = 5432;
        }
        String queryString = dbUri.getQuery();
        if (queryString == null || queryString.isEmpty()) {
            queryString = "ApplicationName=pg-queue-perftest";
        }
        String dbUrl = "jdbc:postgresql://" + dbUri.getHost() + ":" + port + dbUri.getPath() + "?" + queryString;
        BasicDataSource connectionPool = new BasicDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("SET TIME ZONE 'UTC'");
                }
                connection.setAutoCommit(false);
                return connection;
            }
        };

        if (dbUri.getUserInfo() != null) {
            connectionPool.setUsername(dbUri.getUserInfo().split(":", 2)[0]);
            connectionPool.setPassword(dbUri.getUserInfo().split(":", 2)[1]);
        }
        connectionPool.setDriverClassName("org.postgresql.Driver");
        connectionPool.setUrl(dbUrl);
        connectionPool.setInitialSize(1);
        connectionPool.setMaxTotal(-1);
        DatabaseMigrator.migrate(connectionPool);
        return connectionPool;
    }

    public static int getArgumentInt(CommandLine arguments, String optionName, int defaultValue, int minValue) throws ExitException, ParseException {
        if (arguments.hasOption(optionName)) {
            int number = ( (Number) arguments.getParsedOptionValue(optionName) ).intValue();
            if (number < minValue) {
                throw new ExitException("--" + optionName + " should be atleast " + minValue, 1);
            }
            return number;
        } else {
            return defaultValue;
        }
    }

    public static double getArgumentDouble(CommandLine arguments, String optionName, double defaultValue) throws ExitException, ParseException {
        if (arguments.hasOption(optionName)) {
            double number = ( (Number) arguments.getParsedOptionValue(optionName) ).doubleValue();
            if (number < 0) {
                throw new ExitException("--" + optionName + " should be atleast 0", 1);
            }
            return number;
        } else {
            return defaultValue;
        }
    }

    public static final QueueStorageAbstraction<Job> STORAGE_ABSTRACTION = new QueueStorageAbstraction<Job>() {
        String[] COLUMN_LIST = "uuid,seq".split(",");

        @Override
        public String[] columnList() {
            return COLUMN_LIST;
        }

        @Override
        public Job createJob(ResultSet resultSet, int column) throws SQLException {
            String uuid = resultSet.getString(column++);
            long seq = resultSet.getLong(column++);
            return new Job(uuid, seq);
        }

        @Override
        public void saveJob(Job job, PreparedStatement stmt, int column) throws SQLException {
            stmt.setString(column++, job.getUuid());
            stmt.setLong(column++, job.getSeq());
        }
    };

}
