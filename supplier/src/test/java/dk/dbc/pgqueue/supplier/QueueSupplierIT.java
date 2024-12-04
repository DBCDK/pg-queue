/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-supplier
 *
 * dbc-pg-queue-supplier is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-supplier is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.supplier;

import dk.dbc.commons.testcontainers.postgres.DBCPostgreSQLContainer;
import dk.dbc.pgqueue.common.QueueStorageAbstraction;
import dk.dbc.pgqueue.common.DatabaseMigrator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.MountableFile;

import static org.junit.Assert.*;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class QueueSupplierIT {

    private static final String SQL_FILE = "queue-example.sql";

    private static final DBCPostgreSQLContainer PG = makePG();

    private static DBCPostgreSQLContainer makePG() {
        DBCPostgreSQLContainer pg = new DBCPostgreSQLContainer();
        pg.start();
        return pg;
    }

    @BeforeAll
    public static void setUp() throws Exception {
        PG.copyFileToContainer(MountableFile.forClasspathResource(SQL_FILE), "/tmp/");
        String connectString = "postgres://" + PG.getUsername() + ":" + PG.getPassword() + "@localhost/" + PG.getDatabaseName();
        Container.ExecResult result = PG.execInContainer(StandardCharsets.UTF_8, "psql", "--file=/tmp/" + SQL_FILE, connectString);
        System.out.println(result.getStdout());
        if (result.getExitCode() != 0) {
            System.err.println(result.getStderr());
            throw new IllegalStateException("Cannot load: " + SQL_FILE);
        }
        DatabaseMigrator.migrate(PG.datasource());
    }

    @BeforeEach
    public void clearTables() throws SQLException {
        try (Connection connection = PG.createConnection() ;
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("TRUNCATE queue");
            stmt.executeUpdate("TRUNCATE queue_error");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testEnqueue() throws Exception {
        try (Connection connection = PG.createConnection() ;
             PreparedQueueSupplier<String> supplier = new QueueSupplier<>(QUEUE_STORAGE_ABSTRACTION).preparedSupplier(connection) ;
             Statement stmt = connection.createStatement()) {
            supplier.enqueue("a", "#1");
            try (ResultSet resultSet = stmt.executeQuery("SELECT queued, dequeueAfter, dequeueAfter-queued FROM queue")) {
                if (!resultSet.next()) {
                    fail("No rows in queue");
                }
                Timestamp queued = resultSet.getTimestamp(1);
                Timestamp dequeueAfter = resultSet.getTimestamp(2);
                long milliDiff = ( dequeueAfter.getTime() - queued.getTime() + 1L ) / 2L * 2L; // resolution 2ms
                assertEquals(0L, milliDiff);
                if (resultSet.next()) {
                    fail("Too many rows in queue");
                }
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testEnqueuePostpones() throws Exception {
        try (Connection connection = PG.createConnection() ;
             PreparedQueueSupplier<String> supplier = new QueueSupplier<>(QUEUE_STORAGE_ABSTRACTION).preparedSupplier(connection) ;
             Statement stmt = connection.createStatement()) {
            supplier.enqueue("a", "#1", 1500);
            try (ResultSet resultSet = stmt.executeQuery("SELECT queued, dequeueAfter FROM queue")) {
                if (!resultSet.next()) {
                    fail("No rows in queue");
                }
                Timestamp queued = resultSet.getTimestamp(1);
                Timestamp dequeueAfter = resultSet.getTimestamp(2);
                long milliDiff = ( dequeueAfter.getTime() - queued.getTime() + 1L ) / 2L * 2L; // resolution 2ms
                assertEquals(1500L, milliDiff);
                if (resultSet.next()) {
                    fail("Too many rows in queue");
                }
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void batchEnqueue() throws Exception {
        System.out.println("batchEnqueue");
        try (Connection connection = PG.createConnection()) {
            try (BatchQueueSupplier<String> supplier = new QueueSupplier<>(QUEUE_STORAGE_ABSTRACTION).batchSupplier(connection, 10)) {

                try (Statement stmt = connection.createStatement() ;
                     ResultSet resultSet = stmt.executeQuery("SELECT count(*) FROM queue")) {
                    assertTrue(resultSet.next());
                    assertEquals(0, resultSet.getInt(1));
                }
                for (int i = 0 ; i < 15 ; i++) {
                    supplier.enqueue("a", "#" + i);
                }
                try (Statement stmt = connection.createStatement() ;
                     ResultSet resultSet = stmt.executeQuery("SELECT count(*) FROM queue")) {
                    assertTrue(resultSet.next());
                    assertEquals(10, resultSet.getInt(1));
                }
            }
            try (Statement stmt = connection.createStatement() ;
                 ResultSet resultSet = stmt.executeQuery("SELECT count(*) FROM queue")) {
                assertTrue(resultSet.next());
                assertEquals(15, resultSet.getInt(1));
            }
        }
    }

    private static final QueueStorageAbstraction<String> QUEUE_STORAGE_ABSTRACTION = new QueueStorageAbstraction<String>() {
        String[] COLUMN_LIST = new String[] {"job"};

        @Override
        public String[] columnList() {
            return COLUMN_LIST;
        }

        @Override
        public String createJob(ResultSet resultSet, int startColumn) throws SQLException {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void saveJob(String job, PreparedStatement stmt, int startColumn) throws SQLException {
            stmt.setString(startColumn, job);
        }
    };

}
