package dk.dbc.pgqueue.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

public class BasicHarvester<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BasicHarvester.class);
    public final Settings<T> settings;
    protected final DataSource dataSource;
    protected final String selectSql;
    protected final String batchSelectSql;
    protected final String retrySql;
    protected final String postponeSql;
    protected final String failedSql;
    protected final String deleteDuplicateSql;
    protected final int duplicateDeleteColumnsCount;
    final MetricAbstraction.Timer databaseconnectTimer;
    final MetricAbstraction.Timer dequeueTimer;
    final MetricAbstraction.Timer deleteDuplicateTimer;
    final MetricAbstraction.Timer retryTimer;
    final MetricAbstraction.Timer postponeTimer;
    final MetricAbstraction.Timer failureTimer;
    final MetricAbstraction.Timer timestampTimer;
    final MetricAbstraction.Counter rescanCounter;
    final MetricAbstraction.Counter recalcPreparedStatementCounter;

    protected volatile boolean running;

    public BasicHarvester(Settings<T> config, DataSource dataSource) {
        settings = config;
        this.dataSource = dataSource;
        String jobColumns = String.join(", ", config.storageAbstraction.columnList());
        selectSql = String.format(SqlSelect.SQL, config.window, jobColumns);
        batchSelectSql = String.format(SqlBatchSelect.SQL, jobColumns);
        int positionalArgumentsCount = config.storageAbstraction.columnList().length;
        String jobSqlPlaceholders = String.join(", ", Collections.nCopies(positionalArgumentsCount, "?"));
        retrySql = String.format(SqlInsert.SQL, jobColumns, JobMetaData.RETRY_PLACEHOLDER, jobSqlPlaceholders);
        postponeSql = String.format(SqlInsert.SQL, jobColumns, JobMetaData.POSTPONED_PLACEHOLDER, jobSqlPlaceholders);
        failedSql = String.format(SqlFailed.SQL, jobColumns, jobSqlPlaceholders);
        if (config.deduplicateAbstraction == null) {
            deleteDuplicateSql = null;
            duplicateDeleteColumnsCount = 0;
        } else {
            String[] duplicateDeleteColumns = config.deduplicateAbstraction.duplicateDeleteColumnList();
            duplicateDeleteColumnsCount = duplicateDeleteColumns.length;
            String whereClause = Arrays.stream(duplicateDeleteColumns)
                    .map(s -> s + "=?")
                    .collect(Collectors.joining(" AND "));
            deleteDuplicateSql = config.includePostponedInDeduplication
                    ? String.format(SqlDeleteDuplicateIncludePostponed.SQL, whereClause, whereClause, jobColumns)
                    : String.format(SqlDeleteDuplicate.SQL, whereClause, whereClause, jobColumns);
        }
        databaseconnectTimer = makeTimer("databaseconnect");
        dequeueTimer = makeTimer("dequeue");
        deleteDuplicateTimer = makeTimer("deleteDuplicate");
        retryTimer = makeTimer("retry");
        postponeTimer = makeTimer("postpone");
        failureTimer = makeTimer("failure");
        timestampTimer = makeTimer("timestamp");
        rescanCounter = makeCounter("rescan");
        recalcPreparedStatementCounter = makeCounter("recalcPreparedStatement");
    }

    /**
     * Provide a database connection from a {@link DataSource}, that is in
     * transaction state
     *
     * @return new Database connection
     * @throws SQLException
     */
    protected Connection getConnection() throws SQLException {
        try (MetricAbstraction.Timer.Context time = databaseconnectTimer.time()) {
            Connection connection = dataSource.getConnection();
            try {
                connection.setAutoCommit(false);
            } catch (SQLException ex1) {
                LOGGER.error("Error setting autocommit: {}", ex1.getMessage());
                LOGGER.debug("Error setting autocommit:", ex1);
                try {
                    connection.close();
                } catch (SQLException ex2) {
                    LOGGER.error("Error closing connection after failure to set autocommit: {}", ex2.getMessage());
                    LOGGER.debug("Error closing connection after failure to set autocommit:", ex2);
                }
            }
            return connection;
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

    public String getBatchSelectSql() {
        return batchSelectSql;
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

    private MetricAbstraction.Timer makeTimer(String name) {
        return settings.metricAbstraction.timer(Harvester.class, name);
    }

    private MetricAbstraction.Counter makeCounter(String name) {
        return settings.metricAbstraction.counter(Harvester.class, name);
    }

    public static class SqlQueueTimestamp {
        public static final String SQL = "SELECT dequeueAfter FROM queue WHERE consumer=?" +
                " AND dequeueAfter<=clock_timestamp()" +
                " ORDER BY consumer, dequeueAfter" + // hit existing index
                " LIMIT 1";
        public static final int CONSUMER_POS = 1;
    }

    public static class SqlCurrentTimestamp {
        static final String SQL = "SELECT clock_timestamp() - 500 * INTERVAL '1 MILLISECONDS'";
    }

    public static class SqlSelect {
        public static final String SQL = "DELETE" +
                " FROM queue" +
                " WHERE ctid = (SELECT ctid FROM queue WHERE consumer=?" +
                " AND dequeueAfter<=clock_timestamp()" +
                " AND dequeueAfter>=?::TIMESTAMP - INTERVAL '%d MILLISECONDS'" +
                " ORDER BY consumer, dequeueAfter" + // hit existing index
                " FOR UPDATE SKIP LOCKED" +
                " LIMIT 1)" +
                " RETURNING " + JobMetaData.COLUMNS + ", %s";
        public static final int CONSUMER_POS = 1;
        public static final int TIMESTAMP_POS = 2;

    }

    public static class SqlBatchSelect {
        public static final String SQL = "DELETE" +
                " FROM queue" +
                " WHERE ctid in (SELECT ctid FROM queue WHERE consumer=?" +
                " AND dequeueAfter<=clock_timestamp()" +
                " ORDER BY consumer, dequeueAfter" + // hit existing index
                " FOR UPDATE SKIP LOCKED" +
                " LIMIT ?)" +
                " RETURNING " + JobMetaData.COLUMNS + ", %s";
        public static final int CONSUMER_POS = 1;
        public static final int LIMIT_POS = 2;
    }

    public static class SqlDeleteDuplicate {
        public static final String SQL = "DELETE" +
                " FROM queue" +
                " WHERE consumer=?" +
                " AND dequeueAfter<=clock_timestamp()" +
                " AND CTID IN (SELECT CTID FROM queue WHERE consumer = ? AND %s FOR UPDATE SKIP LOCKED)" +
                " AND %s" +
                " RETURNING " + JobMetaData.COLUMNS + ", %s";
    }

    public static class SqlDeleteDuplicateIncludePostponed {
        public static final String SQL = "DELETE" +
                " FROM queue" +
                " WHERE consumer=?" +
                " AND CTID IN (SELECT CTID FROM queue WHERE consumer = ? AND %s FOR UPDATE SKIP LOCKED)" +
                " AND %s" +
                " RETURNING " + JobMetaData.COLUMNS + ", %s";
    }

    public static class SqlDeleteDuplicatePositions {
        public static final int CONSUMER_POS_1 = 1;
        public static final int CONSUMER_POS_2 = 2;
        public static final int DUPLICATE_POS = 3;
    }

    public static class SqlInsert {
        public static final String SQL = "INSERT INTO queue (" +
                JobMetaData.COLUMNS + ", %s)" +
                " VALUES(%s, %s)";
    }

    public static class SqlFailed {
        public static final String SQL = "INSERT INTO queue_error(consumer, queued, diag, %s) VALUES(?, ?, ?, %s)";
        public static final int CONSUMER_POS = 1;
        public static final int QUEUED_POS = 2;
        public static final int DIAG_POS = 3;
        public static final int NEXT_POS = 4;
    }
}
