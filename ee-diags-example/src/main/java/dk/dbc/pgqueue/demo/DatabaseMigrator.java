package dk.dbc.pgqueue.demo;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
@Singleton
@Startup
public class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    @Resource(lookup = "jdbc/pgqueue-ee-example")
    DataSource dataSource;

    @PostConstruct
    public void init() {
        log.info("Migrating database");
        dk.dbc.pgqueue.DatabaseMigrator.migrate(dataSource);
    }

}
