package dk.dbc.pgqueue.ee.diags;

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
     * @throws SQLException when resultset cannot be processed
     */
    String format(ResultSet resultSet) throws SQLException;

}
