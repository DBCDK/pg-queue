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
package dk.dbc.pgqueue.replayer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class Database {

    private static final Pattern POSTGRES_URL_REGEX = Pattern.compile("(?:postgres(?:ql)?://)?(?:([^:@]+)(?::([^@]*))@)?([^:/]+)(?::([1-9][0-9]*))?/(.+)");

    /**
     * Convert a login string into a datasource
     *
     * @param url login string in for form: user:pass@host:port/base with
     *            user, pass and port as optional (password cannot be set without user)
     * @return datasource (not validated, might connect to nothing)
     */
    public static DataSource of(String url) {
        Matcher matcher = POSTGRES_URL_REGEX.matcher(url);
        if (!matcher.matches())
            throw new IllegalArgumentException("Invalid database url");

        String user = matcher.group(1);
        String pass = matcher.group(2);
        String host = matcher.group(3);
        String port = matcher.group(4);
        String base = matcher.group(5);

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        if (user != null)
            dataSource.setUser(user);
        if (pass != null)
            dataSource.setPassword(pass);
        dataSource.setServerNames(new String[] {host});
        if (port != null)
            dataSource.setPortNumbers(new int[] {Integer.parseInt(port)});
        dataSource.setDatabaseName(base);
        return dataSource;
    }
}
