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

import dk.dbc.commons.testutils.postgres.connection.PostgresITDataSource;
import dk.dbc.pgqueue.DatabaseMigrator;
import dk.dbc.pgqueue.QueueStorageAbstraction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import dk.dbc.pgqueue.DeduplicateAbstraction;
import java.io.PrintWriter;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.Ignore;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class HarvesterIT {

    private static PostgresITDataSource pg;
    private static DataSource dataSource;

    @Before
    public void setUp() throws Exception {
        pg = new PostgresITDataSource("pgqueue");
        dataSource = pg.getDataSource();
        try (Connection connection = dataSource.getConnection() ;
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DROP SCHEMA public CASCADE");
            stmt.executeUpdate("CREATE SCHEMA public");
            stmt.executeUpdate("CREATE TABLE queue ( job TEXT NOT NULL )");
            stmt.executeUpdate("CREATE TABLE queue_error ( job TEXT NOT NULL )");
        }
        pg.clearTables("queue", "queue_error");
        DatabaseMigrator.migrate(dataSource);
    }

    @Test(timeout = 5_000L)
    public void testMultipleQueuesOrdered() throws Exception {
        System.out.println("testMultipleQueuesOrdered");
        ArrayList<String> jobs = new ArrayList<>();

        JobConsumer<String> consumer = (JobConsumer<String>) (Connection c, String job, JobMetaData metaData) -> {
            System.out.println("job = " + job + "; meta = " + metaData);
            synchronized (jobs) {
                jobs.add(job);
                jobs.notifyAll();
            }
        };
        QueueWorker queueWorker = QueueWorker.builder(STORAGE_ABSTRACTION)
                .dataSource(dataSource)
                .emptyQueueSleep(200)
                .maxTries(2)
                .consume("foo", "bar")
                .build(consumer);

        queue("foo", "0", "1", "2", "3", "4");
        queue("bar", "a", "b", "c", "d", "e");
        queue("foo", "5", "6", "7", "8", "9");
        queue("bar", "f", "g", "h", "i", "j");
        queueWorker.start();
        synchronized (jobs) {
            while (jobs.size() != 20) {
                jobs.wait();
            }
        }
        queueWorker.stop();
        queueWorker.awaitTermination(250, TimeUnit.MILLISECONDS);

        System.out.println("jobs = " + jobs);
        assertEquals(Arrays.asList("0,1,2,3,4,5,6,7,8,9,a,b,c,d,e,f,g,h,i,j".split(",")), jobs);
    }

    @Test(timeout = 5_000L)
    public void testFailPostpone() throws Exception {
        System.out.println("testFailPostpone");
        ArrayList<String> jobs = new ArrayList<>();

        JobConsumer<String> consumer = (JobConsumer<String>) (Connection c, String job, JobMetaData metaData) -> {
            System.out.println("job = " + job + "; meta = " + metaData);
            synchronized (jobs) {
                jobs.add(job);
                jobs.notifyAll();
                if (jobs.size() == 2) {
                    System.out.println("ERROR on: " + job);
                    throw new PostponedNonFatalQueueError("Error #1", 100);
                }
            }
        };
        QueueWorker queueWorker = QueueWorker.builder(STORAGE_ABSTRACTION)
                .dataSource(dataSource)
                .emptyQueueSleep(200)
                .maxTries(2)
                .consume("foo", "bar")
                .build(consumer);

        queue("foo", "0", "1", "2", "3", "4");
        queueWorker.start();
        synchronized (jobs) {
            while (jobs.size() != 6) {
                jobs.wait();
                System.out.println("jobs = " + jobs);
            }
        }
        queueWorker.stop();
        queueWorker.awaitTermination(250, TimeUnit.MILLISECONDS);

        System.out.println("jobs = " + jobs);
        assertEquals(Arrays.asList("0,1,2,3,4,1".split(",")), jobs);
    }

    @Test(timeout = 5_000L)
    public void testMultiFail() throws Exception {
        System.out.println("testMultiFail");
        ArrayList<String> jobs = new ArrayList<>();

        JobConsumer<String> consumer = (JobConsumer<String>) (Connection c, String job, JobMetaData metaData) -> {
            System.out.println("job = " + job + "; meta = " + metaData);
            synchronized (jobs) {
                jobs.add(job);
                jobs.notifyAll();
                if (job.equals("0")) {
                    System.out.println("ERROR on: " + job);
                    throw new NonFatalQueueError("Error #1");
                }
            }
        };
        QueueWorker queueWorker = QueueWorker.builder(STORAGE_ABSTRACTION)
                .dataSource(dataSource)
                .emptyQueueSleep(200)
                .maxTries(2)
                .consume("foo", "bar")
                .build(consumer);

        queue("foo", "0", "1");
        queueWorker.start();
        synchronized (jobs) {
            while (jobs.size() != 3) {
                jobs.wait();
                System.out.println("jobs = " + jobs);
            }
        }
        queueWorker.stop();
        queueWorker.awaitTermination(250, TimeUnit.MILLISECONDS);

        System.out.println("jobs = " + jobs);
        System.out.println("failedJobs = " + failedJobs());
        assertEquals(Arrays.asList("0,0,1".split(",")), jobs);
        assertEquals(Arrays.asList("0".split(",")), failedJobs());
    }

    @Test(timeout = 5_000L)
    public void testFatal() throws Exception {
        System.out.println("testFatal");
        ArrayList<String> jobs = new ArrayList<>();

        JobConsumer<String> consumer = (JobConsumer<String>) (Connection c, String job, JobMetaData metaData) -> {
            System.out.println("job = " + job + "; meta = " + metaData);
            synchronized (jobs) {
                jobs.add(job);
                jobs.notifyAll();
                if (job.equals("0")) {
                    System.out.println("ERROR on: " + job);
                    throw new FatalQueueError("Error #1");
                }
            }
        };
        QueueWorker queueWorker = QueueWorker.builder(STORAGE_ABSTRACTION)
                .dataSource(dataSource)
                .emptyQueueSleep(200)
                .maxTries(2)
                .consume("foo", "bar")
                .build(consumer);

        queue("foo", "0", "1");
        queueWorker.start();
        synchronized (jobs) {
            while (jobs.size() != 2) {
                jobs.wait();
                System.out.println("jobs = " + jobs);
            }
        }
        queueWorker.stop();
        queueWorker.awaitTermination(250, TimeUnit.MILLISECONDS);

        System.out.println("jobs = " + jobs);
        assertEquals(Arrays.asList("0,1".split(",")), jobs);
        assertEquals(Arrays.asList("0".split(",")), failedJobs());
    }

    @Test(timeout = 5_000L)
    public void testDeduplication() throws Exception {
        System.out.println("testDeduplication");
        ArrayList<String> jobs = new ArrayList<>();

        JobConsumer<String> consumer = (JobConsumer<String>) (Connection c, String job, JobMetaData metaData) -> {
            System.out.println("job = " + job + "; meta = " + metaData);
            synchronized (jobs) {
                jobs.add(job);
                jobs.notifyAll();
            }
        };
        QueueWorker queueWorker = QueueWorker.builder(STORAGE_ABSTRACTION)
                .dataSource(dataSource)
                .emptyQueueSleep(200)
                .maxTries(2)
                .consume("foo", "bar")
                .skipDuplicateJobs(DEDUPLICATE_ABSTRACTION)
                .build(consumer);

        queue("foo", "1", "1", "1", "1", "2", "2", "2"); // collapse into 2 processings
        queuePostponed("foo", 60, "1", "3"); // Ensure this isn't consumed (not ready for dequeue)
        queueWorker.start();
        synchronized (jobs) {
            while (jobs.size() != 2) {
                jobs.wait();
                System.out.println("jobs = " + jobs);
            }
        }
        queueWorker.stop();
        queueWorker.awaitTermination(250, TimeUnit.MILLISECONDS);

        System.out.println("jobs = " + jobs);
        ArrayList<String> remainingJobs = queueRemainingJobs("foo");
        System.out.println("remainingJobs = " + remainingJobs);

        assertEquals(Arrays.asList("1,2".split(",")), jobs);
        assertEquals(Arrays.asList("1,3".split(",")), remainingJobs);
    }

    private void queue(String queueName, String... jobs) throws SQLException {
        try (Connection connection = dataSource.getConnection() ;
             PreparedStatement stmt = connection.prepareStatement("INSERT INTO queue(consumer, job) VALUES(?, ?)")) {
            stmt.setString(1, queueName);
            for (String job : jobs) {
                stmt.setString(2, job);
                stmt.executeUpdate();
            }
        }
    }

    private void queuePostponed(String queueName, int timeout, String... jobs) throws SQLException {
        try (Connection connection = dataSource.getConnection() ;
             PreparedStatement stmt = connection.prepareStatement("INSERT INTO queue(consumer, dequeueAfter, job) VALUES(?, now() + ? * INTERVAL '1 seconds', ?)")) {
            stmt.setString(1, queueName);
            stmt.setInt(2, timeout);
            for (String job : jobs) {
                stmt.setString(3, job);
                stmt.executeUpdate();
            }
        }
    }

    private ArrayList<String> queueRemainingJobs(String queueName) throws SQLException {
        ArrayList<String> ret = new ArrayList<>();
        try (Connection connection = dataSource.getConnection() ;
             PreparedStatement stmt = connection.prepareStatement("SELECT job FROM queue WHERE consumer = ?")) {
            stmt.setString(1, queueName);
            try (ResultSet resultSet = stmt.executeQuery()) {
                while (resultSet.next()) {
                    ret.add(resultSet.getString(1));
                }
            }
        }
        return ret;
    }

    private List<String> failedJobs() throws SQLException {
        ArrayList<String> res = new ArrayList<>();
        try (Connection connection = dataSource.getConnection() ;
             PreparedStatement stmt = connection.prepareStatement("SELECT job FROM queue_error ORDER BY queued") ;
             ResultSet resultSet = stmt.executeQuery()) {
            while (resultSet.next()) {
                res.add(resultSet.getString(1));
            }
        }
        return res;
    }

    private static final QueueStorageAbstraction<String> STORAGE_ABSTRACTION = new QueueStorageAbstraction<String>() {
        String[] COLUMNS = new String[] {"job"};

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
    private static final DeduplicateAbstraction<String> DEDUPLICATE_ABSTRACTION = new DeduplicateAbstraction<String>() {
        String[] COLUMNS = new String[] {"job"};

        @Override
        public String[] duplicateDeleteColumnList() {
            return COLUMNS;
        }

        @Override
        public void duplicateValues(String job, PreparedStatement stmt, int startColumn) throws SQLException {
            stmt.setString(startColumn, job);
        }

        @Override
        public String mergeJob(String originalJob, String skippedJob) {
            System.out.println("skippedJob = " + skippedJob);
            return originalJob;
        }
    };

}
