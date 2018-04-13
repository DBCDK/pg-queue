package dk.dbc.pgqueue.diags;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import javax.ejb.LocalBean;
import javax.ejb.Singleton;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.sql.DataSource;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@LocalBean
/**
 * Bean for accessing queue and queue_error from the database
 * <p>
 * This is a s singleton, and a status object will be maintained, which will be
 * cached.
 * <p>
 * The load on the database could be significant, if caching isn't used.
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class QueueStatusBean {

    private static final ObjectMapper O = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(QueueStatusBean.class);

    private static final ObjectNode QUEUE_STATUS = makeQueueStatueFramework();

    @Resource(type = ManagedExecutorService.class)
    private ExecutorService es;

    private static ObjectNode makeQueueStatueFramework() {
        ObjectNode root = O.createObjectNode();
        root.putObject("props");
        return root;
    }

    /**
     * Produce a json resonse regarding the state of the queue
     *
     * @param dataSource          Where to get the database connections from
     *                            (multiple are used)
     * @param maxCacheAge         Maximum number of seconds to cache (after
     *                            request has been completed)
     *                            This should be reasonably low for monitoring,
     *                            but high enough to lessen database load
     * @param diagPercentMatch    How much a diag should match before being
     *                            collapsed
     * @param diagCollapseMaxRows how many diags to look at (at most) when
     *                            finding diag types
     * @param ignoreQueues        set of queue names to ignore when getting max
     *                            age
     * @return Response containing json structure
     */
    public Response getQueueStatus(DataSource dataSource, long maxCacheAge, int diagPercentMatch, int diagCollapseMaxRows, Set<String> ignoreQueues) {
        log.info("getQueueStatus called");
        try {
            synchronized (QUEUE_STATUS) {
                ObjectNode props = (ObjectNode) QUEUE_STATUS.get("props");
                JsonNode expires = props.get("expires");
                if (expires != null && Instant.parse(expires.asText()).isAfter(Instant.now())) {
                    props.put("cached", Boolean.TRUE);
                } else {
                    Instant preTime = Instant.now();
                    QUEUE_STATUS.removeAll();
                    props = QUEUE_STATUS.putObject("props");
                    props.put("cached", Boolean.FALSE);
                    QUEUE_STATUS.put("queue", "Internal Error");
                    QUEUE_STATUS.put("queue-max-age", "NaN");
                    QUEUE_STATUS.put("diag", "Internal Error");
                    QUEUE_STATUS.put("diag-count", "NaN");
                    Future<JsonNode> queue = es.submit(() -> createQueueStatusNode(dataSource));
                    Future<Integer> diagCount = es.submit(() -> createDiagCount(dataSource));
                    Future<JsonNode> diag = es.submit(() -> createDiagStatusNode(dataSource, diagPercentMatch, diagCollapseMaxRows));

                    JsonNode queueNode = queue.get();
                    QUEUE_STATUS.set("queue", queueNode);
                    int queueMaxAge = 0;
                    if (queueNode.isObject()) {
                        for (Iterator<Map.Entry<String, JsonNode>> iterator = ( (ObjectNode) queueNode ).fields() ; iterator.hasNext() ;) {
                            Map.Entry<String, JsonNode> queueEntry = iterator.next();
                            if (!ignoreQueues.contains(queueEntry.getKey())) {
                                JsonNode node = queueEntry.getValue();
                                int age = node.get("age").asInt();
                                queueMaxAge = Integer.max(queueMaxAge, age);
                            }
                        }
                    }
                    QUEUE_STATUS.put("queue-max-age", queueMaxAge);

                    int diagCountNumber = diagCount.get();
                    QUEUE_STATUS.set("diag", diag.get());
                    QUEUE_STATUS.put("diag-count", diagCountNumber);
                    if (diagCountNumber > diagCollapseMaxRows) {
                        QUEUE_STATUS.put("diag-count-warning", "Too many diag rows for collapsing, only looking at " + diagCollapseMaxRows + " rows");
                    }
                    Instant postTime = Instant.now();
                    long duration = preTime.until(postTime, ChronoUnit.MILLIS);
                    props.put("query-time(ms)", duration);
                    long seconds = Long.min(maxCacheAge, (long) Math.pow(2.0, Math.log(duration)));
                    props.put("will-cache(s)", seconds);
                    props.put("run-at", postTime.toString());
                    props.put("expires", postTime.plusSeconds(seconds).toString());
                }
                return Response.ok().entity(O.writeValueAsString(QUEUE_STATUS)).build();
            }
        } catch (InterruptedException | ExecutionException ex) {
            log.error("Error getting queue status: {}", ex.getMessage());
            log.debug("Error getting queue status: ", ex);
            return Response.status(Response.Status.REQUEST_TIMEOUT).build();
        } catch (JsonProcessingException ex) {
            log.error("Error getting queue status: {}", ex.getMessage());
            log.debug("Error getting queue status: ", ex);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Produce a structure listing when a diag type has occured
     *
     *
     * @param timeZoneName        The timezone in which the diag distribution
     *                            should be shown
     * @param dataSource          Where to get the database connections from
     *                            (multiple are used)
     * @param diagPercentMatch    How much a diag should match before being
     *                            collapsed
     * @param diagCollapseMaxRows how many diags to look at (at most) when
     *                            finding diag types
     * @return Response containing json structure
     */
    public Response getDiagDistribution(String timeZoneName, DataSource dataSource, int diagPercentMatch, int diagCollapseMaxRows) {
        log.info("getDiagDistribution");
        try {
            ZoneId zone = ZoneId.of(timeZoneName);
            ObjectNode obj = O.createObjectNode();
            JsonNode ret = obj;
            JsonNode node = createDiagStatusNode(dataSource, diagPercentMatch, diagCollapseMaxRows);
            if (node.isObject()) {
                HashMap<String, Future<JsonNode>> futures = new HashMap<>();
                for (Iterator<String> iterator = node.fieldNames() ; iterator.hasNext() ;) {
                    String pattern = iterator.next();
                    futures.put(pattern, es.submit(() -> listDiagsByTime(dataSource, pattern, zone)));
                }
                for (Map.Entry<String, Future<JsonNode>> entry : futures.entrySet()) {
                    obj.set(entry.getKey(), entry.getValue().get());
                }
            } else {
                ret = node;
            }
            return Response.ok().entity(O.writeValueAsString(ret)).build();
        } catch (InterruptedException | ExecutionException ex) {
            log.error("Error getting queue status: {}", ex.getMessage());
            log.debug("Error getting queue status: ", ex);
            return Response.status(Response.Status.REQUEST_TIMEOUT).build();
        } catch (JsonProcessingException ex) {
            log.error("Error getting queue status: {}", ex.getMessage());
            log.debug("Error getting queue status: ", ex);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private JsonNode createQueueStatusNode(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection() ;
             Statement stmt = connection.createStatement() ;
             PreparedStatement prepStmt = connection.prepareStatement("SELECT CAST(EXTRACT('epoch' FROM NOW() - dequeueafter) AS INTEGER) FROM queue WHERE consumer = ? ORDER BY dequeueafter LIMIT 1") ;
             ResultSet resultSet = stmt.executeQuery("SELECT consumer, COUNT(*) FROM queue GROUP BY consumer")) {
            ObjectNode node = O.createObjectNode();
            while (resultSet.next()) {
                int i = 0;
                String consumer = resultSet.getString(++i);
                int count = resultSet.getInt(++i);
                Integer age = null;
                prepStmt.setString(1, consumer);
                try (ResultSet preResultSet = prepStmt.executeQuery()) {
                    if (preResultSet.next()) {
                        age = preResultSet.getInt(1);
                    }
                }
                ObjectNode scope = node.putObject(consumer);
                scope.put("count", count);
                scope.put("age", age);
            }
            return node;
        } catch (SQLException ex) {
            log.error("Sql error counting queue entries: {}", ex.getMessage());
            log.debug("Sql error counting queue entries: ", ex);
            return new TextNode("SQL Exception");
        }
    }

    private JsonNode createDiagStatusNode(DataSource dataSource, int diagPercentMatch, int diagCollapseMaxRows) {
        HashMap<ArrayList<String>, AtomicInteger> diags = new HashMap<>();
        try (Connection connection = dataSource.getConnection() ;
             Statement stmt = connection.createStatement() ;
             ResultSet resultSet = stmt.executeQuery("SELECT diag FROM queue_error LIMIT " + diagCollapseMaxRows)) {
            while (resultSet.next()) {
                addToDiags(diags, resultSet.getString(1), diagPercentMatch);
            }
        } catch (SQLException ex) {
            log.error("Sql error counting queue entries: {}", ex.getMessage());
            log.debug("Sql error counting queue entries: ", ex);
            return new TextNode("SQL Exception");
        }
        ObjectNode node = O.createObjectNode();
        Map<ArrayList<String>, String> sortKey = diags.keySet().stream()
                .collect(Collectors.toMap(a -> a, a -> diagAsString(a)));
        diags.entrySet().stream()
                .sorted((l, r) -> sortKey.get(l.getKey()).compareToIgnoreCase(sortKey.get(r.getKey())))
                .forEach(e -> {
                    node.put(sortKey.get(e.getKey()), e.getValue().get());
                });
        return node;
    }

    private Integer createDiagCount(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection() ;
             Statement stmt = connection.createStatement() ;
             ResultSet resultSet = stmt.executeQuery("SELECT COUNT(*) FROM queue_error")) {
            if (resultSet.next()) {
                return resultSet.getInt(1);
            }
        } catch (SQLException ex) {
            log.error("Sql error counting queue entries: {}", ex.getMessage());
            log.debug("Sql error counting queue entries: ", ex);
        }
        return null;
    }

    private JsonNode listDiagsByTime(DataSource dataSource, String pattern, ZoneId zone) {
        ObjectNode obj = O.createObjectNode();
        String likePattern = pattern.replaceAll("([_%])", "\\$1").replaceAll("\\*", "%");
        log.debug("pattern = {}; likePattern = {}", pattern, likePattern);
        try (Connection connection = dataSource.getConnection() ;
             PreparedStatement stmt = connection.prepareStatement("SELECT DATE_TRUNC('MINUTE',failedat) AS at, COUNT(*) FROM queue_error WHERE diag LIKE ? GROUP BY at ORDER BY at")) {
            stmt.setString(1, likePattern);
            try (ResultSet resultSet = stmt.executeQuery()) {
                while (resultSet.next()) {
                    obj.put(resultSet.getTimestamp(1).toInstant().atZone(zone).toString(), resultSet.getInt(2));
                }
            }
            return obj;
        } catch (SQLException ex) {
            log.error("Sql error grouping diags by time: {}", ex.getMessage());
            log.debug("Sql error grouping diags by time: ", ex);
            return new TextNode("SQL Exception");
        }
    }

    private void addToDiags(HashMap<ArrayList<String>, AtomicInteger> accumulated, String text, int diagPercentMatch) {
        ArrayList<String> target = null;
        int lenLeftTarget = 0;
        int lenRightTarget = 0;
        int lenTotalTarget = 0;
        List<String> diag = Arrays.asList(text.split("\\b"));
        int lenDiag = diag.size();
        for (ArrayList<String> candidate : accumulated.keySet()) {
            int lenLeft = diagLenLeft(candidate, diag);
            if (lenLeft == lenDiag) {
                accumulated.get(candidate).incrementAndGet();
                return;
            }
            int lenRight = diagLenRight(candidate, diag);
            int lenTotal = lenLeft + lenRight;
            if (( lenTotal > lenTotalTarget ) ||
                ( lenTotal == lenTotalTarget && lenRight > lenRightTarget )) { // Prioritize longer trailing match (failure reason)
                lenLeftTarget = lenLeft;
                lenRightTarget = lenRight;
                lenTotalTarget = lenTotal;
                target = candidate;
            }
        }
        if (target != null) {
            if (target.size() == ( 1 + lenTotalTarget ) && target.get(lenLeftTarget).isEmpty()) {
                accumulated.get(target).incrementAndGet();
                return;
            } else {
                int maxLen = Integer.max(diag.size(), target.size());
                if (lenTotalTarget >= maxLen * diagPercentMatch / 100) {
                    AtomicInteger integer = accumulated.remove(target);
                    integer.incrementAndGet();
                    target.clear();
                    target.addAll(diag.subList(0, lenLeftTarget));
                    target.add("");
                    int len = diag.size();
                    target.addAll(diag.subList(len - lenRightTarget, len));
                    accumulated.put(target, integer);
                    return;
                }
            }
        }
        target = new ArrayList<>(diag);
        accumulated.put(target, new AtomicInteger(1));
    }

    private int diagLenRight(List<String> combined, List<String> errs) {
        combined = new ArrayList<>(combined);
        errs = new ArrayList<>(errs);
        Collections.reverse(combined);
        Collections.reverse(errs);
        Iterator<String> ci = combined.iterator();
        Iterator<String> ei = errs.iterator();
        int lenRight = 0;
        while (ci.hasNext() && ei.hasNext()) {
            String cstr = ci.next();
            String estr = ei.next();
            if (cstr.equals(estr)) {
                lenRight++;
            } else {
                break;
            }
        }
        return lenRight;
    }

    private int diagLenLeft(List<String> combined, List<String> errs) {
        Iterator<String> ci = combined.iterator();
        Iterator<String> ei = errs.iterator();
        int lenLeft = 0;
        while (ci.hasNext() && ei.hasNext()) {
            String cstr = ci.next();
            String estr = ei.next();
            if (cstr.equals(estr)) {
                lenLeft++;
            } else {
                break;
            }
        }
        return lenLeft;
    }

    private String diagAsString(ArrayList<String> pattern) {
        return pattern.stream()
                .map(s -> s.isEmpty() ? "*" : s)
                .collect(Collectors.joining());
    }

}
