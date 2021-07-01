/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-common
 *
 * dbc-pg-queue-common is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-common is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.common;

import dk.dbc.commons.testcontainers.postgres.DBCPostgreSQLContainer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.MountableFile;

import static org.junit.Assert.*;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class DatabaseMigratorIT {

    private static final String SQL_FILE = "queue-example.sql";

    @ClassRule
    public static DBCPostgreSQLContainer pg = new DBCPostgreSQLContainer();

    @BeforeClass
    public static void setUp() throws Exception {
        pg.copyFileToContainer(MountableFile.forClasspathResource(SQL_FILE), "/tmp/");
        String connectString = "postgres://" + pg.getUsername() + ":" + pg.getPassword() + "@localhost/" + pg.getDatabaseName();
        Container.ExecResult result = pg.execInContainer(StandardCharsets.UTF_8, "psql", "--file=/tmp/" + SQL_FILE, connectString);
        System.out.println(result.getStdout());
        if (result.getExitCode() != 0) {
            System.err.println(result.getStderr());
            throw new IllegalStateException("Cannot load: " + SQL_FILE);
        }
    }

    @Test
    public void testMigrate() throws Exception {
        System.out.println("migrate");
        DatabaseMigrator.migrate(pg.datasource());
        try (Connection connection = pg.createConnection() ;
             Statement stmt = connection.createStatement() ;
             ResultSet resultSet = stmt.executeQuery("SELECT consumer, queued, dequeueAfter, tries FROM queue")) {
            if (!resultSet.next()) {
                fail("No columns");
            }
        }
    }

}
