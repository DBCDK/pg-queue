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

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link QueueWorker}, that
 *
 *
 * @author DBC {@literal <dbc.dk>}
 * @param <T> Job type
 */
class Harvester<T> implements QueueWorker {

    private static final Logger log = LoggerFactory.getLogger(Harvester.class);

    static class SqlQueueTimestamp {

        static final String SQL = "SELECT dequeueAfter FROM queue WHERE consumer=?" +
                                  " AND dequeueAfter<=clock_timestamp()" +
                                  " ORDER BY consumer, dequeueAfter" + // hit existing index
                                  " LIMIT 1";
        static final int CONSUMER_POS = 1;
    }

    static class SqlCurrentTimestamp {

        static final String SQL = "SELECT clock_timestamp() - 500 * INTERVAL '1 MILLISECONDS'";
    }

    static class SqlSelect {

        private static final String SQL = "DELETE" +
                                          " FROM queue" +
                                          " WHERE ctid = (SELECT ctid FROM queue WHERE consumer=?" +
                                          " AND dequeueAfter<=clock_timestamp()" +
                                          " AND dequeueAfter>=?::TIMESTAMP - INTERVAL '%d MILLISECONDS'" +
                                          " ORDER BY consumer, dequeueAfter" + // hit existing index
                                          " FOR UPDATE SKIP LOCKED" +
                                          " LIMIT 1)" +
                                          " RETURNING " + JobMetaData.COLUMNS + ", %s";
        static final int CONSUMER_POS = 1;
        static final int TIMESTAMP_POS = 2;

    }

    static class SqlDeleteDuplicate {

        private static final String SQL = "DELETE" +
                                          " FROM queue" +
                                          " WHERE consumer=?" +
                                          " AND dequeueAfter<=clock_timestamp()" +
                                          " AND CTID IN (SELECT CTID FROM queue WHERE consumer = ? AND %s FOR UPDATE SKIP LOCKED)" +
                                          " AND %s" +
                                          " RETURNING " + JobMetaData.COLUMNS + ", %s";
        static final int CONSUMER_POS_1 = 1;
        static final int CONSUMER_POS_2 = 2;
        static final int DUPLICATE_POS = 3;
    }

    private static class SqlInsert {

        private static final String SQL = "INSERT INTO queue (" +
                                          JobMetaData.COLUMNS + ", %s)" +
                                          " VALUES(%s, %s)";
    }

    static class SqlFailed {

        private static final String SQL = "INSERT INTO queue_error(consumer, queued, diag, %s) VALUES(?, ?, ?, %s)";
        static final int CONSUMER_POS = 1;
        static final int QUEUED_POS = 2;
        static final int DIAG_POS = 3;
        static final int NEXT_POS = 4;

    }

    final Settings<T> settings;
    private final DataSource dataSource;
    private final List<JobWorker<T>> workers;

    private final String selectSql;
    private final String retrySql;
    private final String postponeSql;
    private final String failedSql;
    private final String deleteDuplicateSql;
    private final int duplicateDeleteColumnsCount;

    final Timer databaseconnectTimer;
    final Timer dequeueTimer;
    final Timer deleteDuplicateTimer;
    final Timer retryTimer;
    final Timer postponeTimer;
    final Timer failureTimer;
    final Timer timestampTimer;
    final Counter rescanCounter;
    final Counter recalcPreparedStatementCounter;

    private volatile boolean running;

    Harvester(Settings<T> config, DataSource dataSource, Collection<JobConsumer<T>> consumers) {
        this.settings = config;
        this.dataSource = dataSource;
        this.running = false;
        String jobColumns = String.join(", ", config.storageAbstraction.columnList());
        selectSql = String.format(SqlSelect.SQL, config.window, jobColumns);
        this.workers = consumers.stream()
                .map(c -> new JobWorker<>(c, this, config.health))
                .collect(Collectors.toList());
        int positionalArgumentsCount = config.storageAbstraction.columnList().length;
        String jobSqlPlaceholders = String.join(
                ", ",
                Collections.nCopies(positionalArgumentsCount, "?"));

        this.retrySql = String.format(SqlInsert.SQL, jobColumns, JobMetaData.RETRY_PLACEHOLDER, jobSqlPlaceholders);
        this.postponeSql = String.format(SqlInsert.SQL, jobColumns, JobMetaData.POSTPONED_PLACEHOLDER, jobSqlPlaceholders);
        this.failedSql = String.format(SqlFailed.SQL, jobColumns, jobSqlPlaceholders);
        if (config.deduplicateAbstraction == null) {
            this.deleteDuplicateSql = null;
            this.duplicateDeleteColumnsCount = 0;
        } else {
            String[] duplicateDeleteColumns = config.deduplicateAbstraction.duplicateDeleteColumnList();
            this.duplicateDeleteColumnsCount = duplicateDeleteColumns.length;
            String whereClause = Arrays.stream(duplicateDeleteColumns)
                    .map(s -> s + "=?")
                    .collect(Collectors.joining(" AND "));
            this.deleteDuplicateSql = String.format(SqlDeleteDuplicate.SQL, whereClause, whereClause, jobColumns);
        }
        this.databaseconnectTimer = makeTimer("databaseconnect");
        this.dequeueTimer = makeTimer("dequeue");
        this.deleteDuplicateTimer = makeTimer("deleteDuplicate");
        this.retryTimer = makeTimer("retry");
        this.postponeTimer = makeTimer("postpone");
        this.failureTimer = makeTimer("failure");
        this.timestampTimer = makeTimer("timestamp");
        this.rescanCounter = makeCounter("rescan");
        this.recalcPreparedStatementCounter = makeCounter("recalcPreparedStatement");
    }

    private Timer makeTimer(String name) {
        return settings.metricRegistry.timer(MetricRegistry.name(Harvester.class, name));
    }

    private Counter makeCounter(String name) {
        return settings.metricRegistry.counter(MetricRegistry.name(Harvester.class, name));
    }

    @Override
    public void start() {
        if (running) {
            throw new IllegalStateException("Consumer has already been started");
        }
        running = true;
        for (JobWorker<T> worker : workers) {
            settings.executor.execute(worker);
        }
    }

    @Override
    public void stop() {
        if (!running) {
            throw new IllegalStateException("Consumer is not running");
        }
        running = false;
        for (JobWorker<T> worker : workers) {
            worker.cancel();
        }
    }

    @Override
    public void awaitTermination(long timeout, TimeUnit tu) {
        if (running) {
            throw new IllegalStateException("Consumer is not stopped");
        }
        settings.executor.shutdown();
        try {
            boolean terminated = settings.executor.awaitTermination(timeout, TimeUnit.MILLISECONDS);
            if (!terminated) {
                log.error("Error waiting for harvester-threads to finish: timed out");
            }
        } catch (InterruptedException ex) {
            log.error("Error waiting for harvester-threads to finish: {}", ex.getMessage());
            log.debug("Error waiting for harvester-threads to finish:", ex);
        }
    }

    @Override
    public List<String> hungThreads() {
        return settings.health.hungThreads();
    }

    /**
     * Provide a database connection from a {@link DataSource}, that is in
     * transaction state
     *
     * @return new Database connection
     * @throws SQLException
     */
    Connection getConnectionThrottled() throws SQLException {
        settings.databaseConnectThrottle.throttle();
        boolean success = false;
        try (Timer.Context time = databaseconnectTimer.time()) {
            Connection connection = dataSource.getConnection();
            try {
                connection.setAutoCommit(false);
            } catch (SQLException ex1) {
                log.error("Error setting autocommit: {}", ex1.getMessage());
                log.debug("Error setting autocommit:", ex1);
                try {
                    connection.close();
                } catch (SQLException ex2) {
                    log.error("Error closing connection after failure to set autocommit: {}", ex2.getMessage());
                    log.debug("Error closing connection after failure to set autocommit:", ex2);
                }
            }
            success = true;
            return connection;
        } finally {
            settings.databaseConnectThrottle.register(success);
        }
    }

    /**
     * Get the SQL statement for retrieving a job from the queue
     *
     * @return SQL statement
     */
    String getSelectSql() {
        return selectSql;
    }

    /**
     * Get the SQL statement for retrying a job
     *
     * @return SQL statement
     */
    String getRetrySql() {
        return retrySql;
    }

    /**
     * Get the SQL statement for postponed retry of a job
     *
     * @return SQL statement
     */
    String getPostponeSql() {
        return postponeSql;
    }

    /**
     * Get the SQL statement for storing failed a diagnostics record
     *
     * @return SQL statement
     */
    String getFailedSql() {
        return failedSql;
    }

    /**
     * Get the SQL statement for removing duplicate jobs
     *
     * @return SQL statement or null if duplicate job removal isn't enabled
     */
    String getDeleteDuplicateSql() {
        return deleteDuplicateSql;
    }

    public int getDuplicateDeleteColumnsCount() {
        return duplicateDeleteColumnsCount;
    }

    /**
     * Is this still running or should we abort.
     *
     * @return is QueueHarvester is supposed to be running.
     */
    boolean isRunning() {
        return running;
    }
}
