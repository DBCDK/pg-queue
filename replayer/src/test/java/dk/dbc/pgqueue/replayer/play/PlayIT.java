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

import dk.dbc.commons.testcontainers.postgres.DBCPostgreSQLContainer;
import dk.dbc.pgqueue.common.DatabaseMigrator;
import dk.dbc.pgqueue.replayer.ExitException;
import dk.dbc.pgqueue.replayer.GenericJobMapper;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.junit.Assert.fail;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class PlayIT {

    private static final String QUEUE_NAME = "mine";
    private static final long OFFSET_SLOP = 15L;

    private static final DBCPostgreSQLContainer PG = makePG();

    private static DBCPostgreSQLContainer makePG() {
        DBCPostgreSQLContainer pg = new DBCPostgreSQLContainer();
        pg.start();

        try (Connection connection = pg.createConnection();
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DROP SCHEMA PUBLIC CASCADE");
            stmt.executeUpdate("CREATE SCHEMA PUBLIC");
            stmt.executeUpdate("CREATE TABLE queue( ape TEXT, badger INT, caterpillar JSONB )");
            stmt.executeUpdate("CREATE TABLE queue_error AS SELECT * FROM queue");
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot start up database", ex);
        }
        DatabaseMigrator.migrate(pg.datasource());
        return pg;
    }


    @BeforeEach
    public void cleanDatabase() throws Exception {
        try (Connection connection = PG.createConnection();
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("TRUNCATE queue");
            stmt.executeUpdate("TRUNCATE queue_error");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testPlay() throws Exception {
        System.out.println("testPlay");

        InputStream is = getClass().getClassLoader().getResourceAsStream("play-test.csv");
        GenericJobMapper mapper = GenericJobMapper.from(PG.datasource());
        try (Play play = new Play(PG.datasource(), Long.MAX_VALUE, Long.MAX_VALUE, is, QUEUE_NAME, 1, mapper)) {
            play.run();
            fail("expected end of input exception");
        } catch (ExitException ex) {
            assertThat(ex.getStatusCode(), is(Arguments.EXIT_END_OF_INPUT));
        }
        try (Connection connection = PG.createConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery("SELECT dequeueafter, ape, badger, caterpillar FROM queue ORDER BY dequeueAfter")) {
            assertThat(resultSet.next(), is(true));
            Instant firstTimestamp = resultSet.getTimestamp(1).toInstant();
            System.out.println("firstTimestamp = " + firstTimestamp);
            assertThat(resultSet.getString(2), nullValue());

            assertThat(resultSet.next(), is(true));
            long databaseOffset1 = Duration.between(firstTimestamp, resultSet.getTimestamp(1).toInstant()).toMillis();
            assertThat(databaseOffset1, near(40L));
            assertThat(resultSet.getString(2), is("any"));

            assertThat(resultSet.next(), is(true));
            long databaseOffset2 = Duration.between(firstTimestamp, resultSet.getTimestamp(1).toInstant()).toMillis();
            assertThat(databaseOffset2, near(65L));
            assertThat(resultSet.getString(2), is("me"));
            assertThat(resultSet.getString(3), nullValue()); // Ensure null is passed through

            assertThat(resultSet.next(), is(false));
        }
    }

    private static Matcher<Long> near(long offset) {
        return CoreMatchers.allOf(Matchers.greaterThanOrEqualTo(offset - OFFSET_SLOP), Matchers.lessThanOrEqualTo(offset + OFFSET_SLOP));
    }
}
