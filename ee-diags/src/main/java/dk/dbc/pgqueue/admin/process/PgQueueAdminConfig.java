/*
 * Copyright (C) 2018 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-ee-diags
 *
 * dbc-pg-queue-ee-diags is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-ee-diags is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.admin.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import javax.annotation.PostConstruct;
import javax.ejb.EJBException;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
@Singleton
@Startup
public class PgQueueAdminConfig {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PgQueueAdminConfig.class);

    private static final ObjectMapper O = new ObjectMapper();
    private DataSource dataSource;
    private JobLogMapper jobLogMapper;
    private int diagPercentMatch;
    private int diagCollapseMaxRows;
    private long maxCacheAge;

    @PostConstruct
    public void init() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("/pg-queue-endpoint-config.json")) {
            if (is == null) {
                throw new EJBException("Cannot find `pg-queue-endpoint-config.json' resource");
            }
            JsonNode node = O.readTree(is);

            JsonNode dataSourceNameNode = node.get("dataSource");
            if (dataSourceNameNode == null || !dataSourceNameNode.isTextual()) {
                throw new EJBException("Cannot read `pg-queue-endpoint-config.json' resource: dataSource not undefined");
            }
            log.info("Using: dataSource=" + dataSourceNameNode.asText());
            this.dataSource = lookupDataSource(dataSourceNameNode.asText());

            JsonNode jobLogMapperNameNode = node.get("jobLogMapper");
            if (jobLogMapperNameNode == null || !jobLogMapperNameNode.isTextual()) {
                throw new EJBException("Cannot read `pg-queue-endpoint-config.json' resource: jobLogMapper not undefined");
            }
            log.info("Using: jobLogMapper=" + jobLogMapperNameNode.asText());
            this.jobLogMapper = findJobLogMapper(jobLogMapperNameNode.asText());

            JsonNode diagPercentMatchNode = node.get("diagPercentMatch");
            if (diagPercentMatchNode == null) {
                log.info("Using: diagPercentMatch=90 (default)");
                this.diagPercentMatch = 90;
            } else if (!diagPercentMatchNode.isNumber()) {
                throw new EJBException("Cannot read `pg-queue-endpoint-config.json' resource: diagPercentMatch not a number");
            } else {
                log.info("Using: diagPercentMatch=" + diagPercentMatchNode.asInt());
                this.diagPercentMatch = diagPercentMatchNode.asInt();
            }

            JsonNode diagCollapseMaxRowsNode = node.get("diagCollapseMaxRows");
            if (diagCollapseMaxRowsNode == null) {
                log.info("Using: diagCollapseMaxRows=12500 (default)");
                this.diagCollapseMaxRows = 12500;
            } else if (!diagCollapseMaxRowsNode.isNumber()) {
                throw new EJBException("Cannot read `pg-queue-endpoint-config.json' resource: diagCollapseMaxRows not a number");
            } else {
                log.info("Using: diagCollapseMaxRows="+diagCollapseMaxRowsNode.asText());
                this.diagCollapseMaxRows = diagCollapseMaxRowsNode.asInt();
            }

            JsonNode maxCacheAgeNode = node.get("maxCacheAge");
            if (maxCacheAgeNode == null) {
                log.info("Using: maxCacheAge=45 (default)");
                this.maxCacheAge = 45L;
            } else if (!maxCacheAgeNode.isNumber()) {
                throw new EJBException("Cannot read `pg-queue-endpoint-config.json' resource: maxCacheAge not a number");
            } else {
                log.info("Using: maxCacheAge=" + maxCacheAgeNode.asLong());
                this.maxCacheAge = maxCacheAgeNode.asLong();
            }

        } catch (IOException ex) {
            log.error("Error accessing configuration: {}", ex.getMessage());
            log.debug("Error accessing configuration: ", ex);
            throw new EJBException("Cannot read `pg-queue-endpoint-config.json' resource", ex);
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public JobLogMapper getJobLogMapper() {
        return jobLogMapper;
    }

    public int getDiagPercentMatch() {
        return diagPercentMatch;
    }

    public long getMaxCacheAge() {
        return maxCacheAge;
    }

    public int getDiagCollapseMaxRows() {
        return diagCollapseMaxRows;
    }

    JobLogMapper findJobLogMapper(String name) throws SecurityException, EJBException {
        try {
            int idx = name.lastIndexOf(".");
            if (idx < 0) {
                throw new EJBException("Invalid jobLogMapper name " + name + " expected class.STATIC_VARIABLE");
            }
            String className = name.substring(0, idx);
            String fieldName = name.substring(idx + 1);
            Class<?> mapper = getClass().getClassLoader().loadClass(className);
            Field field = mapper.getDeclaredField(fieldName);
            Class<?> declaringClass = field.getType();
            if (JobLogMapper.class.isAssignableFrom(declaringClass)) {
                if (( field.getModifiers() & ( Modifier.STATIC | Modifier.FINAL ) ) == ( Modifier.STATIC | Modifier.FINAL )) {
                    return (JobLogMapper) field.get(null);
                }
            }
            throw new EJBException("Cannot look up jobLogMapper: " + name + " not of type  `static final LogMapper'");
        } catch (NoSuchFieldException | ClassNotFoundException ex) {
            throw new EJBException("Cannot look up jobLogMapper: " + name, ex);
        } catch (IllegalArgumentException | IllegalAccessException ex) {
            throw new EJBException("Cannot access field: " + name + " of jobLogMapper", ex);
        }
    }

    DataSource lookupDataSource(String jndiName) throws EJBException {
        try {
            Object resource = InitialContext.doLookup(jndiName);
            if (resource instanceof DataSource) {
                return (DataSource) resource;
            } else {
                throw new EJBException("Resource " + jndiName + " is not a DataSource");
            }
        } catch (NamingException ex) {
            throw new EJBException("Cannot look up datasource: " + jndiName, ex);
        }
    }

}
