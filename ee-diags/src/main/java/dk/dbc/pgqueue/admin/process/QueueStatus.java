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

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.ejb.EJB;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
@Path("status/queue")
public class QueueStatus {

    @EJB
    PgQueueAdminConfig config;

    @EJB
    QueueStatusBean bean;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@QueryParam("ignore") @DefaultValue("") String ignore,
                        @QueryParam("force") String force) {
        Set<String> ignoreQueues = Arrays.stream(ignore.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return bean.getQueueStatus(config.getDataSource(),
                                   config.getMaxCacheAge(),
                                   config.getDiagPercentMatch(),
                                   config.getDiagCollapseMaxRows(),
                                   ignoreQueues,
                                   force == null);
    }

}
