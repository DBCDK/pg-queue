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

import dk.dbc.commons.testutils.postgres.connection.PostgresITDataSource;
import dk.dbc.pgqueue.DatabaseMigrator;
import dk.dbc.pgqueue.PreparedQueueSupplier;
import dk.dbc.pgqueue.QueueSupplier;
import dk.dbc.pgqueue.replayer.GenericJobMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Iterator;
import javax.sql.DataSource;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class RecordIT {

    private static final String QUEUE_NAME = "mine";
    private static final long OFFSET_SLOP = 1L;

    private DataSource dataSource;

    @BeforeClass
    public static void initDatabase() throws Exception {
        DataSource dataSource = new PostgresITDataSource("queuetest").getDataSource();

        try (Connection connection = dataSource.getConnection() ;
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DROP SCHEMA PUBLIC CASCADE");
            stmt.executeUpdate("CREATE SCHEMA PUBLIC");
            stmt.executeUpdate("CREATE TABLE queue( ape TEXT, badger INT, catapillar JSONB )");
            stmt.executeUpdate("CREATE TABLE queue_error AS SELECT * FROM queue");
        }
        DatabaseMigrator.migrate(dataSource);
    }

    @Before
    public void cleanDatabase() throws Exception {
        this.dataSource = new PostgresITDataSource("queuetest").getDataSource();
        try (Connection connection = dataSource.getConnection() ;
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("TRUNCATE queue");
            stmt.executeUpdate("TRUNCATE queue_error");
        }
    }

    @Test(timeout = 5_000L)
    public void testRecord() throws Exception {
        System.out.println("testRecord");

        GenericJobMapper mapper = GenericJobMapper.from(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            PreparedQueueSupplier<String[]> supplier = new QueueSupplier<>(mapper)
                    .preparedSupplier(connection);
            supplier.enqueue(QUEUE_NAME, job("zero", null, null), 100);
            supplier.enqueue(QUEUE_NAME, job("fourty", 40, "{}"), 140);
            supplier.enqueue(QUEUE_NAME, job("sixtyfive", 65, "{\"a\": true}"), 165);
        }

        String csv;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (Record record = new RecordWithoutTrimQueue(dataSource, bos, mapper)) {
                record.run();
            }
            bos.flush();
            csv = new String(bos.toByteArray(), UTF_8);
            System.out.print(csv);
        }
        Iterator<String> line = Arrays.asList(csv.trim().split("[\r\n]+")).iterator();

        assertThat(line.hasNext(), is(true));
        String header = line.next();
        assertThat(header, is("offsetInMs,ape,badger,catapillar"));

        assertThat(line.hasNext(), is(true));
        String line1 = line.next();
        assertThat(line1, containsString(",,")); // null value in badger column
        assertThat(line1, containsString("zero"));
        long origin = offsetFromLine(line1);

        assertThat(line.hasNext(), is(true));
        String line2 = line.next();
        assertThat(line2, containsString("fourty"));
        long offset2 = offsetFromLine(line2) - origin;
        assertThat(offset2, near(40L));

        assertThat(line.hasNext(), is(true));
        String line3 = line.next();
        assertThat(line3, containsString("sixtyfive"));
        long offset3 = offsetFromLine(line3) - origin;
        assertThat(offset3, near(65L));

        assertThat(line.hasNext(), is(false));
    }

    private static class RecordWithoutTrimQueue extends Record {

        public RecordWithoutTrimQueue(DataSource dataSource, OutputStream output, GenericJobMapper mapper) {
            super(dataSource, 500L, output, QUEUE_NAME, mapper);
        }

        @Override
        void trimQueue() throws SQLException {
            // Not trimming queue - needs data already stored
        }
    }

    private static String[] job(String ape, Integer badger, String catapillar) {
        return new String[] {ape, badger == null ? null : String.valueOf(badger), catapillar};
    }

    private static long offsetFromLine(String line1) throws NumberFormatException {
        return Long.parseLong(line1.split(",")[0].replaceAll("\"", ""));
    }

    private static Matcher<Long> near(long offset) {
        return CoreMatchers.allOf(Matchers.greaterThanOrEqualTo(offset - OFFSET_SLOP), Matchers.lessThanOrEqualTo(offset + OFFSET_SLOP));
    }
}
