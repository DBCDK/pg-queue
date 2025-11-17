package dk.dbc.pgqueue.consumer;

import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface BatchFetcher<T> {
    int batch(int limit, Supplier<JobWithMetaData<T>> currentJob, Consumer<List<JobWithMetaData<T>>> batchConsumer) throws SQLException;
}
