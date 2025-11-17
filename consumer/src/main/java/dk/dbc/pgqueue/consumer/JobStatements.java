package dk.dbc.pgqueue.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class JobStatements<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobStatements.class);
    protected final BasicHarvester<T> harvester;
    protected final HashMap<String, Timestamp> timestamps = new HashMap<>();
    protected Connection connection;
    protected PreparedStatement timestampStmt;
    protected PreparedStatement clockStmt;
    protected PreparedStatement selectStmt;
    protected PreparedStatement retryStmt;
    protected PreparedStatement postponeStmt;
    protected PreparedStatement failedStmt;
    protected PreparedStatement deleteDuplicateStmt;
    protected int fullScanCounter;
    private final QueueHealth health;

    public JobStatements(BasicHarvester<T> harvester) {
        this.harvester = harvester;
        connection = null;
        selectStmt = null;
        retryStmt = null;
        postponeStmt = null;
        failedStmt = null;
        fullScanCounter = harvester.settings.fullScanEvery;
        health = harvester.settings.health;
    }

    /**
     * Construct a prepared statement, if needed, and fill in data
     *
     * @param queue     queue name to dequeue from
     * @param timestamp last known timestamp
     * @return sql statement
     * @throws SQLException for database errors
     */
    protected PreparedStatement getSelectStmt(String queue, Timestamp timestamp) throws SQLException {
        if (selectStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                selectStmt = connection.prepareStatement(harvester.getSelectSql());
                LOGGER.debug("Prepared statement: {}", harvester.getSelectSql());
            }
        }
        selectStmt.setString(Harvester.SqlSelect.CONSUMER_POS, queue);
        selectStmt.setTimestamp(Harvester.SqlSelect.TIMESTAMP_POS, timestamp);
        return selectStmt;
    }

    protected PreparedStatement getBatchSelectStmt(String queue, Timestamp timestamp, int limit) throws SQLException {
        if (selectStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                selectStmt = connection.prepareStatement(harvester.getBatchSelectSql());
                LOGGER.debug("Prepared statement: {}", harvester.getBatchSelectSql());
            }
        }
        selectStmt.setString(BasicHarvester.SqlBatchSelect.CONSUMER_POS, queue);
        selectStmt.setTimestamp(BasicHarvester.SqlBatchSelect.TIMESTAMP_POS, timestamp);
        selectStmt.setInt(BasicHarvester.SqlBatchSelect.LIMIT_POS, limit);
        return selectStmt;
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
     * @param job      job to delete duplicates of
     * @param consumer which consumer to deduplicate for
     * @return result set
     * @throws SQLException from database errors
     */
    private ResultSet timedDeleteDuplicate(JobWithMetaData<T> job, String consumer) throws SQLException {
        PreparedStatement stmt = getDeleteDuplicateStmt(job, consumer);
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
     * Delete duplicate jobs
     *
     * @param job      job to match
     * @param consumer which consumer to deduplicate for
     * @return sql statement
     * @throws SQLException for database errors
     */
    protected PreparedStatement getDeleteDuplicateStmt(JobWithMetaData<T> job, String consumer) throws SQLException {
        if (deleteDuplicateStmt == null) {
            if (harvester.getDeleteDuplicateSql() == null) {
                return null;
            }
            try (QueueHealth.Context call = health.databaseCall()) {
                deleteDuplicateStmt = connection.prepareStatement(harvester.getDeleteDuplicateSql());
            }
        }
        deleteDuplicateStmt.setString(Harvester.SqlDeleteDuplicatePositions.CONSUMER_POS_1, consumer);
        deleteDuplicateStmt.setString(Harvester.SqlDeleteDuplicatePositions.CONSUMER_POS_2, consumer);
        harvester.settings.deduplicateAbstraction
                .duplicateValues(job.getActualJob(), deleteDuplicateStmt, Harvester.SqlDeleteDuplicatePositions.DUPLICATE_POS);
        harvester.settings.deduplicateAbstraction
                .duplicateValues(job.getActualJob(), deleteDuplicateStmt, Harvester.SqlDeleteDuplicatePositions.DUPLICATE_POS + harvester.getDuplicateDeleteColumnsCount());
        return deleteDuplicateStmt;
    }

    /**
     * Update tries count
     *
     * @param job the job and metadata for the queue entry
     * @throws SQLException from database errors
     */
    protected void retryJob(JobWithMetaData<T> job) throws SQLException {
        LOGGER.debug("retrying job");
        int rows;
        try (MetricAbstraction.Timer.Context time = harvester.retryTimer.time() ;
             QueueHealth.Context call = health.databaseCall()) {
            rows = getRetryStmt(job).executeUpdate();
        }
        if (rows != 1) {
            LOGGER.warn("Strange: retrying job, modified rows = " + rows);
        }
    }

    /**
     * Construct a prepared statement, if needed, and fill in data
     *
     * @param job           the job and metadata for the queue entry
     * @return sql statement
     * @throws SQLException for database errors
     */
    private PreparedStatement getRetryStmt(JobWithMetaData<T> job) throws SQLException {
        if (retryStmt == null) {
            try (QueueHealth.Context call = health.databaseCall()) {
                retryStmt = connection.prepareStatement(harvester.getRetrySql());
                LOGGER.debug("Prepared statement: {}", harvester.getRetrySql());
            }
        }
        job.save(retryStmt, 1);
        harvester.settings.storageAbstraction
                .saveJob(job.getActualJob(), retryStmt, 1 + JobMetaData.RETRY_PLACEHOLDER_COUNT);
        return retryStmt;
    }

    /**
     * Update tries count and postpone dequeue
     *
     * @param job         the job and metadata for the queue entry
     * @param postponedMs number of milliseconds to postpone dequeue
     * @throws SQLException from database errors
     */
    protected void postponeJob(JobWithMetaData<T> job, long postponedMs) throws SQLException {
        LOGGER.debug("postpone job for {}ms", postponedMs);
        int rows;
        try (MetricAbstraction.Timer.Context time = harvester.postponeTimer.time() ;
             QueueHealth.Context call = health.databaseCall()) {
            rows = getPostponeStmt(job, postponedMs).executeUpdate();
        }
        if (rows != 1) {
            LOGGER.warn("Strange: postponing job, modified rows = " + rows);
        }
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
                LOGGER.debug("Prepared statement: {}", harvester.getPostponeSql());
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
     * Remove queue entry, and put it into queue_error
     *
     * @param job     the job that failed
     * @param message the reason it failed
     * @throws SQLException from database errors
     */
    protected void failJob(JobWithMetaData<T> job, String message) throws SQLException {
        LOGGER.debug("failing with: {}", message);
        PreparedStatement stmt = getFailedStmt(job, message);
        harvester.settings.storageAbstraction.saveJob(job.getActualJob(), stmt, Harvester.SqlFailed.NEXT_POS);
        int rows;
        try (MetricAbstraction.Timer.Context time = harvester.failureTimer.time();
             QueueHealth.Context call = health.databaseCall()) {
            rows = stmt.executeUpdate();
        }
        if (rows != 1) {
            LOGGER.warn("Strange: writing diag job, modified rows = " + rows);
        }
    }

    /**
     * Get the last seen timestamp for the queue
     *
     * @param queue name of queue
     * @return timestamp (cached or retrieved)
     */
    protected Timestamp getTimestampFor(String queue) throws SQLException {
        try {
            return timestamps.computeIfAbsent(queue, this::getTimestampFromDb);
        } catch (RuntimeException ex) {
            exceptionUnwrap(ex, SQLException.class);
            throw ex;
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
                LOGGER.info("Query took {}ms, making new prepared statements", elapsed);
                harvester.recalcPreparedStatementCounter.inc();
                releasePreparedStmts();
            }
        }
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
                LOGGER.debug("Prepared statement: {}", Harvester.SqlCurrentTimestamp.SQL);
            }
        }
        return clockStmt;
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
                LOGGER.debug("Prepared statement: {}", Harvester.SqlQueueTimestamp.SQL);
            }
        }
        timestampStmt.setString(Harvester.SqlQueueTimestamp.CONSUMER_POS, queue);
        return timestampStmt;
    }

    /**
     * Wrapper around sql method, logging in case of an exception
     *
     * @param method        the method to call
     * @param exceptionText the exception text used for logging errors
     */
    protected static void sql(SQLExceptionMethod method, String exceptionText) {
        try {
            method.accept();
        } catch (SQLException ex) {
            LOGGER.error("{}: {}", exceptionText, ex.getMessage());
            LOGGER.debug("{}:", exceptionText, ex);
        }
    }

    protected String getExceptionMessage(Exception ex) {
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
        LOGGER.debug("Exception message is: {}", message);
        return message;
    }

    private boolean isMessageInList(String message, List<String> messages) {
        return messages.stream()
                .anyMatch(m -> m.contains(message));
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


    /**
     * Acquire a database connection
     *
     * @throws SQLException from database errors
     */
    protected void setupConnection() throws SQLException {
        connection = harvester.getConnection();
    }

    /**
     * Disconnect from database
     */
    protected void releaseConnection() {
        if (connection != null) {
            sql(() -> connection.close(), "Error closing connection");
            connection = null;
        }
    }

    /**
     * Clear all prepared statements
     */
    protected void releasePreparedStmts() {
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

    @FunctionalInterface
    public interface SQLExceptionMethod {
        void accept() throws SQLException;
    }
}
