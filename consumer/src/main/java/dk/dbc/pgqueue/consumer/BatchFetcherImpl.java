package dk.dbc.pgqueue.consumer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BatchFetcherImpl<T> extends JobStatements<T> implements BatchFetcher<T> {
    private final String consumerId;

    public BatchFetcherImpl(String consumerId, BasicHarvester<T> harvester) {
        super(harvester);
        this.consumerId = consumerId;
    }

    @Override
    public int batch(int limit, Supplier<JobWithMetaData<T>> currentJob, Consumer<List<JobWithMetaData<T>>> batchConsumer) throws SQLException {
        connection.setAutoCommit(false);
        List<JobWithMetaData<T>> list = new ArrayList<>();
        try(PreparedStatement ps = getBatchSelectStmt(consumerId, Timestamp.from(Instant.now()), limit);
            ResultSet rs = ps.executeQuery()) {
            while(rs.next()) list.add(new JobWithMetaData<>(rs, 1, harvester.settings.storageAbstraction));
            try {
                batchConsumer.accept(list);
                connection.commit();
            } catch (RuntimeException e) {
                connection.rollback();
                JobWithMetaData<T> job = currentJob.get();
                if(job == null) throw e;
                if (job.getTries() >= harvester.settings.maxTries) {
                    String message = getExceptionMessage(e);
                    failJob(job, message);
                } else {
                    retryJob(job);
                }
                connection.commit();
                throw e;
            }
        }
        return list.size();
    }
}
