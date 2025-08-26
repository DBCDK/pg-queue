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

import dk.dbc.pgqueue.common.QueueStorageAbstraction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * A mapper for an array of Strings to/from a queue job
 * <p>
 * With optional room for extra columns
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class GenericJobMapper implements QueueStorageAbstraction<String[]> {

    static final Set<String> QUEUE_ADMIN_COLUMNS = Set.of(
            "consumer",
            "queued", "dequeueafter", "tries",
            "pk");

    private final String[] columns;
    private final int[] types;
    private int emptyColumnsBefore = 0;

    private GenericJobMapper(String[] columns, int[] types) {
        this.columns = columns;
        this.types = types;
    }

    public int getEmptyColumnsBefore() {
        return emptyColumnsBefore;
    }

    public void setEmptyColumnsBefore(int emptyColumnsBefore) {
        this.emptyColumnsBefore = emptyColumnsBefore;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    @Override
    public String[] columnList() {
        return columns;
    }

    @Override
    public String[] createJob(ResultSet resultSet, int startColumn) throws SQLException {
        String[] objects = new String[emptyColumnsBefore + columns.length];
        for (int i = 0 ; i < types.length ; i++) {
            objects[emptyColumnsBefore + i] = resultSet.getString(startColumn + i);
        }
        return objects;
    }

    @Override
    public void saveJob(String[] job, PreparedStatement stmt, int startColumn) throws SQLException {
        for (int i = 0 ; i < types.length ; i++) {
            Object obj = job[emptyColumnsBefore + i];
            if (obj == null) {
                stmt.setNull(startColumn + i, types[i]);
            } else {
                stmt.setObject(startColumn + i, obj, types[i]);
            }
        }
    }

    /**
     * Generate a StorageAbstraction from the current database
     *
     * @param dataSource database connection
     * @return job mapper, that maps all columns to/from strings
     * @throws SQLException If we cannot determine columns from queue table
     */
    public static GenericJobMapper from(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return from(connection);
        }
    }

    /**
     * Generate a StorageAbstraction from the current database
     *
     * @param connection database connection
     * @return job mapper, that maps all columns to/from strings
     * @throws SQLException If we cannot determine columns from queue table
     */
    public static GenericJobMapper from(Connection connection) throws SQLException {
        HashMap<String, Integer> columnTypes = new HashMap<>();

        try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM queue")) {
            ResultSetMetaData metaData = stmt.getMetaData();
            int columnCount = metaData.getColumnCount();
            for (int i = 1 ; i <= columnCount ; i++) {
                String column = metaData.getColumnName(i);
                if (!QUEUE_ADMIN_COLUMNS.contains(column)) {
                    columnTypes.put(column, metaData.getColumnType(i));
                }
            }
        }
        String[] columns = columnTypes.keySet().stream().sorted().toArray(String[]::new);
        return new GenericJobMapper(columns,
                                    Stream.of(columns).map(columnTypes::get).mapToInt(i -> i).toArray());
    }

}
