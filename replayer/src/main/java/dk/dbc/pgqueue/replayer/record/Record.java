/*
 * Copyright (C) 2020 DBC A/S (http://dbc.dk/)
 *
 * This is part of pg-queue-replayer
 *
 * pg-queue-replayer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * pg-queue-replayer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.replayer.record;

import com.codahale.metrics.MetricRegistry;
import com.opencsv.CSVWriterBuilder;
import com.opencsv.ICSVWriter;
import dk.dbc.pgqueue.consumer.QueueWorker;
import dk.dbc.pgqueue.replayer.ExitException;
import dk.dbc.pgqueue.replayer.GenericJobMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * <p>
 * This could have been it's own project with this an jar entrypoint
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class Record implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Record.class);

    private final DataSource dataSource;
    private final long duration;
    private final OutputStream output;
    private final String queue;
    private final GenericJobMapper mapper;

    /**
     * Run the record option
     *
     * @param args command line arguments from main()
     * @throws ExitException If there's som kind of error that needs to be
     *                       reported with an exit status (commandline arguments)
     * @throws IOException   If reading of cvs file fails
     * @throws SQLException  If dequeueing or analysis of queue-table fails
     */
    public static void main(String[] args) throws ExitException, SQLException, IOException {
        Arguments arguments = new Arguments();
        arguments.parseArguments(args);
        try (Record instance = new Record(arguments)) {
            instance.run();
        }
    }

    // For mocking
    Record(DataSource dataSource, long duration, OutputStream output, String queue, GenericJobMapper mapper) {
        this.dataSource = dataSource;
        this.duration = duration;
        this.output = output;
        this.queue = queue;
        this.mapper = mapper;
        this.mapper.setEmptyColumnsBefore(1);
    }

    /**
     * Set up parameters
     *
     * @param arguments the commandline arguments
     * @throws ExitException If parameters are invalid
     * @throws SQLException  if the jobmapper cannot be generated
     */
    private Record(Arguments arguments) throws ExitException, SQLException {
        this.dataSource = arguments.getDataSource();
        this.duration = arguments.getDurationInMS();
        this.output = arguments.getOutput();
        this.queue = arguments.getQueue();
        this.mapper = GenericJobMapper.from(dataSource);
        mapper.setEmptyColumnsBefore(1); // make room for offsetInMs
    }

    /**
     * Entrypoint for processing file
     * <p>
     * Sets up input and database
     *
     * @throws IOException  If reading of cvs file fails
     * @throws SQLException If enqueueing fails
     */
    void run() throws IOException, SQLException {
        Thread thisThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            thisThread.interrupt();
        }));

        try (OutputStream os = getOutput() ;
             OutputStreamWriter w = new OutputStreamWriter(os, UTF_8)) {
            w.write("offsetInMs," + String.join(",", mapper.columnList()) + "\n");
            w.flush();

            try (ICSVWriter csv = new CSVWriterBuilder(w)
                    .withLineEnd("\n")
                    .build()) {

                JobToCSVConsumer consumer = new JobToCSVConsumer(csv);

                QueueWorker worker = queueWorker(consumer);
                trimQueue();
                consumer.setEpoch();
                worker.start();
                try {
                    log.info("Waiting for {}", duration == Long.MAX_VALUE ? "ever, press Ctrl-C to break" : duration + "ms");
                    Thread.sleep(duration);
                } catch (InterruptedException ex) {
                    log.info("Interrupted");
                }
                log.info("Stopping worker");
                worker.stop();
                worker.awaitTermination(5, TimeUnit.SECONDS);
                log.info("Worker stopped");
                csv.flushQuietly();
                w.flush();
                log.info("A total of {} rows recorded", consumer.getRows());
            }
        }
    }

    /**
     * Make a queue worker with 1 consumer
     *
     * @param consumer consumer to handle queue jobs
     * @return new queue worker
     */
    private QueueWorker queueWorker(JobToCSVConsumer consumer) {
        return QueueWorker.builder(mapper)
                .consume(queue)
                .dataSource(dataSource)
                .emptyQueueSleep(100)
                .window(100)
                .metricRegistryCodahale(new MetricRegistry())
                .build(consumer);
    }

    /**
     * Remove all jobs for this consumer
     *
     * @throws SQLException if jobs cannot be removed
     */
    void trimQueue() throws SQLException {
        log.info("Trimming queue: {}", queue);
        try (Connection connection = dataSource.getConnection() ;
             PreparedStatement stmt = connection.prepareStatement("DELETE FROM queue WHERE consumer=?")) {
            stmt.setString(1, queue);
            int rows = stmt.executeUpdate();
            log.info("Removed {} rows", rows);
        }
    }

    // Workaround to get a member into a try-with-resources
    private OutputStream getOutput() {
        return output;
    }

    @Override
    public void close() throws IOException {
        output.close();
    }
}
