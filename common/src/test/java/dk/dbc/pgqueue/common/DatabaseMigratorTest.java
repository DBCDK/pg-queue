/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-PG-queue-common
 *
 * dbc-PG-queue-common is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-PG-queue-common is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.common;

import dk.dbc.commons.testcontainers.postgres.DBCPostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.MountableFile;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class DatabaseMigratorTest {

    private static final String SQL_FILE = "queue-example.sql";

    private static final DBCPostgreSQLContainer PG = makePG();

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testMigrate() throws Exception {
        System.out.println("migrate");

        PG.copyFileToContainer(MountableFile.forClasspathResource(SQL_FILE), "/tmp/");
        String connectString = "postgres://" + PG.getUsername() + ":" + PG.getPassword() + "@localhost/" + PG.getDatabaseName();
        Container.ExecResult result = PG.execInContainer(StandardCharsets.UTF_8, "psql", "--file=/tmp/" + SQL_FILE, connectString);
        System.out.println(result.getStdout());
        if (result.getExitCode() != 0) {
            System.err.println(result.getStderr());
            throw new IllegalStateException("Cannot load: " + SQL_FILE);
        }

        DatabaseMigrator.migrate(PG.datasource());
        try (Connection connection = PG.createConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery("SELECT consumer, queued, dequeueAfter, tries FROM queue")) {
            if (!resultSet.next()) {
                assertThat("No columns", 0, is(1));
            }
        }
    }

    private static DBCPostgreSQLContainer makePG() {
        DBCPostgreSQLContainer pg = new DBCPostgreSQLContainer();
        pg.start();
        return pg;
    }
}
