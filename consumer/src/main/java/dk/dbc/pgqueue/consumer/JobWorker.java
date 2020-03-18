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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
class JobWorker<T> implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobConsumer<T> consumer;
    private final Harvester<T> harvester;
    private final HashMap<String, Timestamp> timestamps = new HashMap<>();
    private final QueueHealth health;
    private Connection connection;
    private PreparedStatement timestampStmt;
    private PreparedStatement clockStmt;
    private PreparedStatement selectStmt;
    private PreparedStatement retryStmt;
    private PreparedStatement postponeStmt;
    private PreparedStatement failedStmt;
    private PreparedStatement deleteDuplicateStmt;
    private int fullScanCounter;
    private Thread self;

    JobWorker(JobConsumer<T> consumer, Harvester<T> harvester, QueueHealth health) {
        this.consumer = consumer;
        this.harvester = harvester;
        this.connection = null;
        this.selectStmt = null;
        this.retryStmt = null;
        this.postponeStmt = null;
        this.failedStmt = null;
        this.fullScanCounter = harvester.settings.fullScanEvery;
        this.health = health;
    }

    /**
     * Loop over {@link #nextJob() } and {@link #process(dk.dbc.pgqueue.consumer.JobWithMetaData)
     * } as long as harvester isn't canceled
     */
    @Override
    public void run() {
        self = Thread.currentThread();
        while (harvester.isRunning()) {
            try {
                JobWithMetaData<T> job = nextJob();
                if (job == null) {
                    if (harvester.isRunning()) {
                        log.error("Unknown State!!! got no job, but is still running");
                    }
                } else {
                    process(job);
                }
            } catch (SQLException ex) {
                log.error("Error fetching job: {}", ex.getMessage());
                log.debug("Error fetching job:", ex);
                log.info("Disconnecting from database");
                releasePreparedStmts();
                releaseConnection();
            } catch (RuntimeException ex) {
                log.error("Error fetching job: {}", ex.getMessage());
                log.debug("Error fetching job:", ex);
            }
        }
        releasePreparedStmts();
        releaseConnection();
    }

    /**
     * Interrupt self
     */
    void cancel() {
        if (self != null &&
            !self.isInterrupted()) {
            self.interrupt();
        }
    }

    /**
     * Get a job in this workers context.
     * <p>
     * Used by Harvester, for getting a job, that can be used as argument for
     * calling {@link #process(dk.dbc.pgqueue.consumer.Job) }
     *
     * @throws Exception If no connection can be made.
     */
    private JobWithMetaData<T> nextJob() throws SQLException {
        JobWithMetaData<T> job = null;
        while (harvester.isRunning() && job == null) {
            if (connection == null || !connection.isValid(0)) {
                releasePreparedStmts();
                releaseConnection();
                try {
                    harvester.settings.databaseConnectThrottle.throttle();
                    setupConnection();
                    job = fetchJob(false);
                    harvester.settings.databaseConnectThrottle.register(true);
                } catch (SQLException ex) {
                    harvester.settings.databaseConnectThrottle.register(false);
                    throw ex;
                }
            } else {
                job = fetchJob(true);
            }
        }
        log.debug("job = {}", job);
        return job;
    }

    /**
     * Process a job
     * <p>
     * Depending on the exception state of the call to {@link JobConsumer} a job
     * can be:
     * <pre>
     * - removed from queue (Success)
     * - removed from queue and listed in queue_error (Failure)
     * - reset into the queue with new tried count
     * - reset into the queue with new tried count and new dequeueAfter time
     * </pre>
     *
     * @param job The job to process
     */
    private void process(JobWithMetaData<T> job) throws SQLException {
        boolean success = false;
        try {
            Savepoint savepoint = connection.setSavepoint();
            try {
                if (harvester.settings.deduplicateAbstraction != null) {
                    ResultSet resultSet = timedDeleteDuplicate(job);
                    if (resultSet != null) {
                        while (resultSet.next()) {
                            JobWithMetaData<T> skippedJob = new JobWithMetaData<>(resultSet, 1, harvester.settings.storageAbstraction);
                            T actualJob = harvester.settings.deduplicateAbstraction
                                    .mergeJob(job.getActualJob(), skippedJob.getActualJob());
                            job.setActualJob(actualJob);
                            log.info("Skipping job: {}", skippedJob);
                        }
                    }
                }

                consumer.accept(connection, job.getActualJob(), job);
                success = true;
                sql(() -> connection.releaseSavepoint(savepoint), "Release savepoint");
            } catch (FatalQueueError ex) {
                log.info("Fatal error: {}", ex.getMessage());
                log.debug("Fatal error: ", ex);
                connection.rollback(savepoint);
                connection.commit(); // In case of failJob fails
                failJob(job, getExceptionMessage(ex));
            } catch (PostponedNonFatalQueueError ex) {
                log.info("Non Fatal error: {} (postpone)", ex.getMessage());
                log.debug("Non Fatal error: ", ex);
                connection.rollback(savepoint);
                if (job.getTries() >= harvester.settings.maxTries) {
                    connection.commit(); // In case of failJob fails
                    failJob(job, getExceptionMessage(ex));
                } else {
                    postponeJob(job, ex.getPostponedMs());
                }
            } catch (NonFatalQueueError | RuntimeException ex) {
                log.info("Non Fatal error: {}", ex.getMessage());
                log.debug("Non Fatal error: ", ex);
                connection.rollback(savepoint);
                if (job.getTries() >= harvester.settings.maxTries) {
                    String message = getExceptionMessage(ex);
                    connection.commit(); // In case of failJob fails
                    failJob(job, message);
                } else {
                    retryJob(job);
                }
            }
            log.debug("committing");
            connection.commit();
        } catch (SQLException ex) {
            success = false; // in case a commit after a succesfull job fails
            log.error("Rolling back because of: {}", ex.getMessage());
            log.debug("Rolling back because of: ", ex);
            sql(() -> connection.rollback(), "Error rolling back");
            throw ex;
        } finally {
            harvester.settings.failureThrottle.register(success);
        }
    }

    private String getExceptionMessage(Exception ex) {
        List<String> messages = new ArrayList<>(3);
        for (Throwable tw = ex ; tw != null && messages.size() < 3 ; tw = tw.getCause()) {
            String message = tw.getMessage();
            if (message != null && !message.isEmpty() && !isMessageInList(message, messages)) {
                messages.add(message);
            }
        }
        if (messages.isEmpty()) {
            messages = Collections.singletonList("Anonymous " + ex.getClass().getSimpleName());
        }
        String message = String.join(", ", messages);
        log.debug("Exception message is: {}", message);
        return message;
    }

    private boolean isMessageInList(String message, List<String> messages) {
        return messages.stream()
                .anyMatch(m -> m.contains(message));
    }

    /**
     * Acquire a job from the queue, throttling in case of errors
     * <p>
     * Wait and rescan queue as needed
     *
     * @param waitForJob if not set returns null if no job can be fetched
     * @return new job or null if thread has been canceled or not waiting
     * @throws SQLException If an error occurs
     */
    private JobWithMetaData fetchJob(boolean waitForJob) throws SQLException {
        harvester.settings.failureThrottle.throttle();

        boolean hasClearedTimestamps = false;
        // Different value is we're in idle state
        int fullScanEvery = harvester.settings.fullScanEvery;
        while (harvester.isRunning()) {
            if (--fullScanCounter <= 0) {
                log.debug("Clearing remembered timestamps 1 in a {} event",
                          harvester.settings.fullScanEvery);
                harvester.rescanCounter.inc();
                fullScanCounter = fullScanEvery;
                timestamps.clear();
                hasClearedTimestamps = true;
            }
            for (String queueName : harvester.settings.consumerNames) {
                Timestamp timestamp = getTimestampFor(queueName);
                log.debug("Trying to poll job from: " + queueName + " newer than: " + timestamp);
                try (ResultSet resultSet = timedSelect(queueName, timestamp)) {
                    if (resultSet.next()) {
                        JobWithMetaData job = new JobWithMetaData(resultSet, 1, harvester.settings.storageAbstraction);
                        timestamps.put(queueName, job.getDequeueAfter());
                        return job;
                    }
                }
            }
            connection.rollback();
            if (!waitForJob)
                return null;
            // idle state fullscan more often, and start with fullscan
            fullScanEvery = harvester.settings.idleFullScanEvery;
            if (!hasClearedTimestamps) {
                fullScanCounter = 0;
            } else {
                try {
                    log.debug("Got no job - sleeping for {}ms", harvester.settings.emptyQueueSleep);
                    Thread.sleep(harvester.settings.emptyQueueSleep);
                } catch (InterruptedException ex) {
                    if (harvester.isRunning()) {
                        log.error("Error waiting for something to appear on queue: {}", ex.getMessage());
                        log.debug("Error waiting for something to appear on queue:", ex);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Wrap a select in a timer
     *
     * @param queueName name of queue to harvest from
     * @param timestamp How old jobs to look for
     * @return result set
     * @throws SQLException from database errors
     */
    private ResultSet timedSelect(String queueName, Timestamp timestamp) throws SQLException {
        PreparedStatement stmt = getSelectStmt(queueName, timestamp);
        try (MetricAbstraction.Timer.Context time = harvester.dequeueTimer.time() ;
             QueueHealth.Context call = health.databaseCall()) {
            return stmt.executeQuery();
        }
    }

    /**
     * Wrap a delete duplicate in a timer and holour deduplicateDisable
     *
     * @param job job to delete duplicates of
     * @return result set
     * @throws SQLException from database errors
     */
    private ResultSet timedDeleteDuplicate(JobWithMetaData<T> job) throws SQLException {
        PreparedStatement stmt = getDeleteDuplicateStmt(job);
        if (stmt == null) {
            return null;
        }
        if (!harvester.settings.deduplicateDisable.canDeduplicate())
            return null;
        try (MetricAbstraction.Timer.Context time = harvester.deleteDuplicateTimer.time() ;
             DeduplicateDisable.Context dedupDisable = harvester.settings.deduplicateDisable.context() ;
             QueueHealth.Context call = health.databaseCall()) {
            return stmt.executeQuery();
        }
    }

    /**
     * Update tries count
     *
     * @param job the job and metadata for the queue entry
     * @throws SQLException from database errors
     */
    private void retryJob(JobWithMetaData<T> job) throws SQLException {
        log.debug("retrying job");
        int rows;
        try (MetricAbstraction.Timer.Context time = harvester.retryTimer.time() ;
             QueueHealth.Context call = health.databaseCall()) {
            rows = getRetryStmt(job).executeUpdate();
        }
        if (rows != 1) {
            log.warn("Strange: retrying job, modified rows = " + rows);
        }
    }

    /**
     * Update tries count and postpone dequeue
     *
     * @param job         the job and metadata for the queue entry
     * @param postponedMs number of milliseconds to postpone dequeue
     * @throws SQLException from database errors
     */
    private void postponeJob(JobWithMetaData<T> job, long postponedMs) throws SQLException {
        log.debug("postpone job for {}ms", postponedMs);
        int rows;
        try (MetricAbstraction.Timer.Context time = harvester.postponeTimer.time() ;
             QueueHealth.Context call = health.databaseCall()) {
            rows = getPostponeStmt(job, postponedMs).executeUpdate();
        }
        if (rows != 1) {
            log.warn("Strange: postponing job, modified rows = " + rows);
        }
    }

    /**
     * Remove queue entry, and put it into queue_error
     *
     * @param job     the job that failed
     * @param message the reason it failed
     * @throws SQLException from database errors
     */
    private void failJob(JobWithMetaData<T> job, String message) throws SQLException {
        log.debug("failing with: {}", message);
        PreparedStatement stmt = getFailedStmt(job, message);
        harvester.settings.storageAbstraction
                .saveJob(job.getActualJob(),
                         stmt,
                         Harvester.SqlFailed.NEXT_POS);
        int rows;
        try (MetricAbstraction.Timer.Context time = harvester.failureTimer.time() ;
             QueueHealth.Context call = health.databaseCall()) {
            rows = stmt.executeUpdate();
        }
        if (rows != 1) {
            log.warn("Strange: writing diag job, modified rows = " + rows);
        }
    }

    /**
     * Get the last seen timestamp for the queue
     *
     * @param queue name of queue
     * @return timestamp (cached or retrieved)
     */
    private Timestamp getTimestampFor(String queue) throws SQLException {
        try {
            return timestamps.computeIfAbsent(queue, this::getTimestampFromDb);
        } catch (RuntimeException ex) {
            exceptionUnwrap(ex, SQLException.class);
            throw ex;
        }
    }

    /**
     * Acquire a database connection
     *
     * @throws SQLException from database errors
     */
    private void setupConnection() throws SQLException {
        connection = harvester.getConnection();
    }

    /**
     * Disconnect from database
     */
    private void releaseConnection() {
        if (connection != null) {
            sql(() -> connection.close(), "Error closing connection");
            connection = null;
        }
    }

    /**
     * Clear all prepared statements
     */
    private void releasePreparedStmts() {
        if (timestampStmt != null) {
            sql(() -> timestampStmt.close(), "Error closing timestamp statement");
            timestampStmt = null;
        }
        if (clockStmt != null) {
            sql(() -> clockStmt.close(), "Error closing clock statement");
            clockStmt = null;
        }
        if (selectStmt != null) {
            sql(() -> selectStmt.close(), "Error closing select statement");
            selectStmt = null;
        }
        if (retryStmt != null) {
            sql(() -> retryStmt.close(), "Error closing retry statement");
            retryStmt = null;
        }
        if (postponeStmt != null) {
            sql(() -> postponeStmt.close(), "Error closing postpone statement");
            postponeStmt = null;
        }
        if (failedStmt != null) {
            sql(() -> failedStmt.close(), "Error closing failed statement");
            failedStmt = null;
        }
        if (deleteDuplicateStmt != null) {
            sql(() -> deleteDuplicateStmt.close(), "Error closing delete duplicate statement");
            deleteDuplicateStmt = null;
        }
    }

    /**
     * Acquire a timestamp or a .5 second before now
     *
     * @param queue name of queue toe query
     * @return oldest queue entry time
     */
    private Timestamp getTimestampFromDb(String queue) {
        long before = System.currentTimeMillis();
        try (MetricAbstraction.Timer.Context time = harvester.timestampTimer.time()) {
            try (QueueHealth.Context call = health.databaseCall() ;
                 ResultSet resultSet = getTimestampStmt(queue).executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getTimestamp(1);
                }
            }
            try (QueueHealth.Context call = health.databaseCall() ;
                 ResultSet resultSet = getClockStmt().executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getTimestamp(1);
                }
            }
            throw new SQLException("Cannot get timestamp from database");
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            long after = System.currentTimeMillis();
            long elapsed = after - before;
            if (elapsed >= harvester.settings.maxQueryTime) {
                log.info("Query took {}ms, making new prepared statements", elapsed);
                harvester.recalcPreparedStatementCounter.inc();
                releasePreparedStmts();
            }
        }
    }

    /**
     * Construct a prepared statement, if needed, and fill in data
     *
     * @param queue
     * @return sql statement
     * @throws SQLException for database errors
     */
    private PreparedStatement getTimestampStmt(String queue) throws SQLException {
        if (timestampStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                timestampStmt = connection.prepareStatement(Harvester.SqlQueueTimestamp.SQL);
            }
        }
        timestampStmt.setString(Harvester.SqlQueueTimestamp.CONSUMER_POS, queue);
        return timestampStmt;
    }

    /**
     * Construct a prepared statement, if needed
     *
     * @return sql statement
     * @throws SQLException for database errors
     */
    private PreparedStatement getClockStmt() throws SQLException {
        if (clockStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                clockStmt = connection.prepareStatement(Harvester.SqlCurrentTimestamp.SQL);
            }
        }
        return clockStmt;
    }

    /**
     * Construct a prepared statement, if needed, and fill in data
     *
     * @param queue     queue name to dequeue from
     * @param timestamp last known timestamp
     * @return sql statement
     * @throws SQLException for database errors
     */
    private PreparedStatement getSelectStmt(String queue, Timestamp timestamp) throws SQLException {
        if (selectStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                selectStmt = connection.prepareStatement(harvester.getSelectSql());
            }
        }
        selectStmt.setString(Harvester.SqlSelect.CONSUMER_POS, queue);
        selectStmt.setTimestamp(Harvester.SqlSelect.TIMESTAMP_POS, timestamp);
        return selectStmt;
    }

    /**
     * Construct a prepared statement, if needed, and fill in data
     *
     * @param newTriesCount how many times the job has been tried
     * @param job           the job and metadata for the queue entry
     * @return sql statement
     * @throws SQLException for database errors
     */
    private PreparedStatement getRetryStmt(JobWithMetaData<T> job) throws SQLException {
        if (retryStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                retryStmt = connection.prepareStatement(harvester.getRetrySql());
            }
        }
        job.save(retryStmt, 1);
        harvester.settings.storageAbstraction
                .saveJob(job.getActualJob(), retryStmt, 1 + JobMetaData.RETRY_PLACEHOLDER_COUNT);
        return retryStmt;
    }

    /**
     * Construct a prepared statement, if needed, and fill in data
     *
     * @param job          the job and metadata for the queue entry
     * @param milliseconds how long to postpone processing
     * @return sql statement
     * @throws SQLException for database errors
     */
    private PreparedStatement getPostponeStmt(JobWithMetaData<T> job, long milliseconds) throws SQLException {
        if (postponeStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                postponeStmt = connection.prepareStatement(harvester.getPostponeSql());
            }
        }
        job.saveDelayed(postponeStmt, 1, milliseconds);
        harvester.settings.storageAbstraction
                .saveJob(job.getActualJob(), postponeStmt, 1 + JobMetaData.POSTPONED_PLACEHOLDER_COUNT);
        return postponeStmt;
    }

    /**
     * Construct a prepared statement, if needed, and fill in data
     *
     * @param job  old job
     * @param diag the error message
     * @return sql statement
     * @throws SQLException for database errors
     */
    private PreparedStatement getFailedStmt(JobWithMetaData<T> job, String diag) throws SQLException {
        if (failedStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                failedStmt = connection.prepareStatement(harvester.getFailedSql());
            }
        }
        failedStmt.setString(Harvester.SqlFailed.CONSUMER_POS, job.getConsumer());
        failedStmt.setTimestamp(Harvester.SqlFailed.QUEUED_POS, job.getQueued());
        failedStmt.setString(Harvester.SqlFailed.DIAG_POS, diag);
        return failedStmt;
    }

    /**
     * Delete duplicate jobs
     *
     * @param job job to match
     * @return sql statement
     * @throws SQLException for database errors
     */
    private PreparedStatement getDeleteDuplicateStmt(JobWithMetaData<T> job) throws SQLException {
        if (deleteDuplicateStmt == null) {
            if (harvester.getDeleteDuplicateSql() == null) {
                return null;
            }
            try (QueueHealth.Context call = health.databaseCall()) {
                deleteDuplicateStmt = connection.prepareStatement(harvester.getDeleteDuplicateSql());
            }
        }
        deleteDuplicateStmt.setString(Harvester.SqlDeleteDuplicatePositions.CONSUMER_POS_1, job.getConsumer());
        deleteDuplicateStmt.setString(Harvester.SqlDeleteDuplicatePositions.CONSUMER_POS_2, job.getConsumer());
        harvester.settings.deduplicateAbstraction
                .duplicateValues(job.getActualJob(), deleteDuplicateStmt, Harvester.SqlDeleteDuplicatePositions.DUPLICATE_POS);
        harvester.settings.deduplicateAbstraction
                .duplicateValues(job.getActualJob(), deleteDuplicateStmt, Harvester.SqlDeleteDuplicatePositions.DUPLICATE_POS + harvester.getDuplicateDeleteColumnsCount());
        return deleteDuplicateStmt;
    }

    @FunctionalInterface
    private interface SQLExceptionMethod {

        void accept() throws SQLException;
    }

    /**
     * Wrapper around sql method, logging in case of an exception
     *
     * @param method        the method to call
     * @param exceptionText the exception text used for logging errors
     */
    private static void sql(SQLExceptionMethod method, String exceptionText) {
        try {
            method.accept();
        } catch (SQLException ex) {
            log.error("{}: {}", exceptionText, ex.getMessage());
            log.debug("{}:", exceptionText, ex);
        }
    }

    /**
     * Unwrap an exception, throwing it if there's any cause of this type
     *
     * @param <T>   Exception type
     * @param ex    Base exception
     * @param clazz exception class
     * @throws T if found in caused-by
     */
    private static <T extends Exception> void exceptionUnwrap(Exception ex, Class<T> clazz) throws T {
        Throwable t = ex.getCause();
        while (t != null) {
            if (clazz.isAssignableFrom(t.getClass()))
                throw (T) t;
            t = t.getCause();
        }
    }

}
