package dk.dbc.pgqueue.demo;

import dk.dbc.pgqueue.admin.process.JobLogMapper;
import java.sql.ResultSet;
import java.util.Locale;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class Mapper {

    public static final JobLogMapper MAPPER = (ResultSet r) ->
            String.format(Locale.ROOT, "id:%s", r.getString("job"));
}
