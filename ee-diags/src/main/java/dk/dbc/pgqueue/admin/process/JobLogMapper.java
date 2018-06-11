package dk.dbc.pgqueue.admin.process;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public interface JobLogMapper {

    /**
     * The slf4j logger pattern for the job
     *
     * ie. "id:{}, special:{}"
     *
     * @param resultSet job row
     * @return pattern formatted job description
     * @throws java.sql.SQLException
     */
    String format(ResultSet resultSet) throws SQLException;

}
