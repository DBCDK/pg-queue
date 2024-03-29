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
package dk.dbc.pgqueue.ee.diags;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.ejb.EJB;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

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
    public Response get(@Context UriInfo info,
                        @QueryParam("ignore") @DefaultValue("") String ignore) {
        Set<String> ignoreQueues = Arrays.stream(ignore.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return bean.getQueueStatus(config.getDataSource(),
                                   config.getMaxCacheAge(),
                                   config.getDiagPercentMatch(),
                                   config.getDiagCollapseMaxRows(),
                                   ignoreQueues,
                                   info.getQueryParameters().containsKey("force"));
    }

}
