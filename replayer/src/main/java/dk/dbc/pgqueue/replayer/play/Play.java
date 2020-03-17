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
package dk.dbc.pgqueue.replayer.play;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.enums.CSVReaderNullFieldIndicator;
import dk.dbc.pgqueue.PreparedQueueSupplier;
import dk.dbc.pgqueue.QueueSupplier;
import dk.dbc.pgqueue.replayer.ExitException;
import dk.dbc.pgqueue.replayer.GenericJobMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Play a cvs file against a database queue.
 * <p>
 * This could have been its own project with this as a jar entry point
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class Play implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Play.class);

    private final DataSource dataSource;
    private final long duration;
    private final long maxBehind;
    private final InputStream input;
    private final String queue;
    private final double scale;
    private final GenericJobMapper mapper;
    private volatile boolean interrupted;

    /**
     * Run the play option
     *
     * @param args command line arguments from main()
     * @throws ExitException If there's som kind of error that needs to be
     *                       reported with an exit status
     * @throws IOException   If reading of cvs file fails
     * @throws SQLException  If enqueueing or analysis of queue-table fails
     */
    public static void main(String[] args) throws ExitException, IOException, SQLException {
        Arguments arguments = new Arguments();
        arguments.parseArguments(args);
        try (Play instance = new Play(arguments)) {
            instance.run();
        }
    }

    // For mocking
    Play(DataSource dataSource, long duration, long maxBehind, InputStream input, String queue, double scale, GenericJobMapper mapper) {
        this.dataSource = dataSource;
        this.duration = duration;
        this.maxBehind = maxBehind;
        this.input = input;
        this.queue = queue;
        this.scale = scale;
        this.mapper = mapper;
        this.mapper.setEmptyColumnsBefore(1);
        this.interrupted = false;
    }

    /**
     * Set up parameters
     *
     * @param arguments the commandline arguments
     * @throws ExitException If parameters are invalid
     * @throws SQLException  if the jobmapper cannot be generated
     */
    private Play(Arguments arguments) throws ExitException, SQLException {
        this.dataSource = arguments.getDataSource();
        this.duration = arguments.getDurationInMS();
        this.maxBehind = arguments.getMaxBehindInMS();
        this.input = arguments.getInput();
        this.queue = arguments.getQueue();
        this.scale = arguments.getScale();
        this.mapper = GenericJobMapper.from(dataSource);
        mapper.setEmptyColumnsBefore(1); // make room for offsetInMs
        this.interrupted = false;
    }

    /**
     * Entry point for processing file input
     * <p>
     * Sets up input and database
     *
     * @throws ExitException If there's som kind of error that needs to be
     *                       reported with an exit status
     * @throws IOException   If reading of cvs file fails
     * @throws SQLException  If enqueueing fails
     */
    void run() throws ExitException, IOException, SQLException {
        Thread thisThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            interrupted = true;
            thisThread.interrupt();
        }));

        try (InputStreamReader ir = new InputStreamReader(input, UTF_8) ;
             PushbackReader r = new PushbackReader(ir, 1)) {
            stripComments(r);
            try (CSVReader csv = new CSVReaderBuilder(r)
                    .withFieldAsNull(CSVReaderNullFieldIndicator.EMPTY_SEPARATORS)
                    .build()) {
                verifyHeader(csv);
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(true);
                    PreparedQueueSupplier<String[]> enqueue = new QueueSupplier<>(mapper)
                            .preparedSupplier(connection);
                    loop(csv, enqueue);
                }
            }
        } catch (InterruptedException ex) {
            if (interrupted)
                return;
            log.error("Interrupted: {}", ex.getMessage());
            log.debug("Interrupted: ", ex);
        }
    }

    /**
     * Process cvs lines
     *
     * @param csv     cvs file
     * @param enqueue Database queue handler
     * @throws ExitException        If there's som kind of error that needs to
     *                              be reported with an exit status
     * @throws IOException          If reading of cvs file fails
     * @throws SQLException         If enqueueing fails
     * @throws InterruptedException if Control-C is pressed
     */
    private void loop(final CSVReader csv, PreparedQueueSupplier<String[]> enqueue) throws ExitException, IOException, SQLException, InterruptedException {
        Instant start = Instant.now();
        log.info("Starting");
        Instant end = start.plusMillis(duration);
        log.debug("end = {}", end);
        long mostBehind = 0;
        int rows = 0;
        try {
            while (!interrupted) {
                String[] row = csv.readNextSilently();
                if (row == null)
                    throw new ExitException(Arguments.EXIT_END_OF_INPUT, "Ran out of input");
                log.debug("row = {}", Arrays.asList(row));
                rows++;
                long originalOffset = Long.parseUnsignedLong(row[0]);
                long offset = (long) ( scale * (double) originalOffset );
                Instant nextEnqueue = start.plusMillis(offset);
                log.debug("nextEnqueue = {}", nextEnqueue);
                if (end.isBefore(nextEnqueue)) {
                    log.info("Duration completed (no more rows before timeout)");
                    return;
                }
                long millis = Duration.between(Instant.now(), nextEnqueue).toMillis();
                if (millis < 0) {
                    if (millis < -maxBehind)
                        throw new ExitException(Arguments.EXIT_MAX_BEHIND, "Too far behind on queueing");
                    if (mostBehind < millis) {
                        log.warn("We're {}ms behind on queueing - should be multithreaded?");
                        mostBehind = millis;
                    }
                } else if (millis > 0) {
                    Thread.sleep(millis);
                }
                enqueue.enqueue(queue, row);
            }
        } finally {
            log.info("Replayed {} rows", rows);
        }
    }

    /**
     * Ensure the header line in the cvs file matches the database columns
     *
     * @param csv cvs-file
     * @throws ExitException if no header is found or header doesn't match
     * @throws IOException   If the csv file cannot be read
     */
    private void verifyHeader(CSVReader csv) throws ExitException, IOException {
        String[] row = csv.readNextSilently();
        if (row == null) {
            throw new ExitException(1, "No header in csv file");
        }
        String rowAsText = String.join(",", row);
        String expectedText = "offsetInMs," + String.join(",", mapper.columnList());
        if (!rowAsText.equals(expectedText)) {
            log.error("Expected rowheader to be: {}", expectedText);
            log.error("Row header was:           {}", rowAsText);
            throw new ExitException(1);
        }
    }

    /**
     * Remove all leading lines that start with #
     *
     * @param r reader
     * @throws IOException If content cannot be read
     */
    private void stripComments(PushbackReader r) throws IOException {
        log.debug("Stripping comments before header");
        for (;;) {
            int c = r.read();
            if (c == -1) // EOF
                return;
            if (Character.isWhitespace(c)) // Leading whitespace
                continue;
            if (c != '#') { // Not comment
                r.unread(c);
                return;
            }
            // Read remainder of line
            while (c != '\r' && c != '\n') { // Not EOL
                c = r.read();
                if (c == -1) // EOF in comment
                    return;
            }
            while (c == '\r' || c == '\n') { // All EOL
                c = r.read();
                if (c == -1) // EOF after EOL
                    return;
            }
            r.unread(c); // 1st Not EOL
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
