package dk.dbc.pgqueue.admin.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import javax.ejb.LocalBean;
import javax.ejb.Singleton;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.sql.DataSource;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@Singleton
@LocalBean
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
     * @param force               force reload of data... usually only needed in
     *                            testing
     * @return Response containing json structure
     */
    public Response getQueueStatus(DataSource dataSource, long maxCacheAge, int diagPercentMatch, int diagCollapseMaxRows, Set<String> ignoreQueues, boolean force) {
        log.info("getQueueStatus called");

        return jsonResponse(() ->
                queueStatusText(dataSource, diagPercentMatch, diagCollapseMaxRows,
                                maxCacheAge, ignoreQueues, force), "Error getting queue status");
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
     * @param force               disregard caching (can be expensive)
     * @return json as text
     * @throws com.fasterxml.jackson.core.JsonProcessingException Json error
     * @throws java.util.concurrent.ExecutionException            Parallel query
     *                                                            error
     * @throws java.lang.InterruptedException                     Parallel query
     *                                                            error
     */
    public String queueStatusText(DataSource dataSource, int diagPercentMatch, int diagCollapseMaxRows, long maxCacheAge, Set<String> ignoreQueues, boolean force) throws JsonProcessingException, ExecutionException, InterruptedException {
        synchronized (QUEUE_STATUS) {
            ObjectNode props = (ObjectNode) QUEUE_STATUS.get("props");
            JsonNode expires = props.get("expires");
            if (!force && expires != null && Instant.parse(expires.asText()).isAfter(Instant.now())) {
                props.put("cached", Boolean.TRUE);
            } else {
                Instant preTime = Instant.now();
                QUEUE_STATUS.removeAll();
                // The balant misuse of jacksons inststance on having object
                // keys in the order theyre created. Create the keys in the
                // human readble order
                props = QUEUE_STATUS.putObject("props");
                props.put("cached", Boolean.FALSE);
                QUEUE_STATUS.put("queue", "Internal Error");
                QUEUE_STATUS.putPOJO("queue-max-age-skip-list", null);
                QUEUE_STATUS.put("queue-max-age", "NaN");
                QUEUE_STATUS.put("diag", "Internal Error");
                QUEUE_STATUS.put("diag-count", "NaN");
                Future<JsonNode> queue = es.submit(() -> createQueueStatusNode(dataSource));
                Future<Integer> diagCount = es.submit(() -> createDiagCount(dataSource));
                Future<JsonNode> diag = es.submit(() -> createDiagStatusNode(dataSource, diagPercentMatch, diagCollapseMaxRows));

                JsonNode queueNode = queue.get();
                QUEUE_STATUS.set("queue", queueNode);

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

            IgnoreQueues ignore = new IgnoreQueues(ignoreQueues);
            JsonNode queueNode = QUEUE_STATUS.get("queue");
            int queueMaxAge = 0;
            if (queueNode.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> iterator = ( (ObjectNode) queueNode ).fields() ; iterator.hasNext() ;) {
                    Map.Entry<String, JsonNode> queueEntry = iterator.next();
                    if (ignore.keepOrIgnore(queueEntry.getKey())) {
                        JsonNode node = queueEntry.getValue();
                        int age = node.get("age").asInt();
                        queueMaxAge = Integer.max(queueMaxAge, age);
                    }
                }
            }
            QUEUE_STATUS.putPOJO("queue-max-age-skip-list", ignore.getIgnored());
            QUEUE_STATUS.put("queue-max-age", queueMaxAge);
            return O.writeValueAsString(QUEUE_STATUS);
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
        return jsonResponse(() -> {
            ZoneId zone = ZoneId.of(timeZoneName);
            ObjectNode obj = O.createObjectNode();
            JsonNode ret = obj;
            JsonNode node = createDiagStatusNode(dataSource, diagPercentMatch, diagCollapseMaxRows);
            if (node.isObject()) {
                Map<String, Future<Map<String, Integer>>> futures = new HashMap<>();
                for (Iterator<String> iterator = node.fieldNames() ; iterator.hasNext() ;) {
                    String pattern = iterator.next();
                    futures.put(pattern, es.submit(() -> listDiagsByTime(dataSource, pattern, zone)));
                }
                DiagsNode diagsNode = new DiagsNode(obj);
                Map<String, Map<String, Integer>> prDiag = diagToTimestampToCount(futures);
                prDiag.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(diagsNode::putInObject);

                Map<String, Map<String, Integer>> prTime = timestampToDiagToCount(prDiag);
                prTime.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(diagsNode::putInObject);

            } else {
                ret = node;
            }
            return O.writeValueAsString(ret);
        }, "Error getting queue diag distribution");
    }

    private Map<String, Map<String, Integer>> timestampToDiagToCount(Map<String, Map<String, Integer>> prDiag) {
        Map<String, Map<String, Integer>> prTime = new HashMap<>();
        prDiag.forEach((diag, diags) ->
                diags.forEach((timestamp, count) ->
                        prTime.computeIfAbsent(timestamp, x -> new HashMap<>())
                                .put(diag, count)));
        return prTime;
    }

    private Map<String, Map<String, Integer>> diagToTimestampToCount(Map<String, Future<Map<String, Integer>>> futures) throws InterruptedException, ExecutionException {
        Map<String, Map<String, Integer>> prDiag = new HashMap<>();
        for (Map.Entry<String, Future<Map<String, Integer>>> entry : futures.entrySet()) {
            prDiag.put(entry.getKey(), entry.getValue().get());
        }
        return prDiag;
    }

    private static class DiagsNode {

        private final ObjectNode obj;

        public DiagsNode(ObjectNode obj) {
            this.obj = obj;
        }

        private void putInObject(Map.Entry<String, Map<String, Integer>> entry) {
            ObjectNode node = obj.with(entry.getKey());
            entry.getValue().entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> node.put(e.getKey(), e.getValue()));
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

    private HashMap<String, Integer> listDiagsByTime(DataSource dataSource, String pattern, ZoneId zone) {
        HashMap<String, Integer> distribution = new HashMap<>();
        String likePattern = pattern.replaceAll("([_%])", "\\$1").replaceAll("\\*", "%");
        log.debug("pattern = {}; likePattern = {}", pattern, likePattern);
        try (Connection connection = dataSource.getConnection() ;
             PreparedStatement stmt = connection.prepareStatement("SELECT DATE_TRUNC('MINUTE',failedat) AS at, COUNT(*) FROM queue_error WHERE diag LIKE ? GROUP BY at ORDER BY at")) {
            stmt.setString(1, likePattern);
            try (ResultSet resultSet = stmt.executeQuery()) {
                while (resultSet.next()) {
                    distribution.put(resultSet.getTimestamp(1).toInstant().atZone(zone).toString(), resultSet.getInt(2));
                }
            }
            return distribution;
        } catch (SQLException ex) {
            log.error("Sql error grouping diags by time: {}", ex.getMessage());
            log.debug("Sql error grouping diags by time: ", ex);
            distribution.put("SQLException", -1);
        }
        return distribution;
    }

    private void addToDiags(HashMap<ArrayList<String>, AtomicInteger> accumulated, String text, int diagPercentMatch) {
        ArrayList<String> target = null;
        int lenLeftTarget = 0;
        int lenRightTarget = 0;
        int lenTotalTarget = 0;
        List<String> diag = Arrays.asList(text.split("\\b"));
        int lenDiag = diag.size();
        for (Map.Entry<ArrayList<String>, AtomicInteger> candidate : accumulated.entrySet()) {
            ArrayList<String> key = candidate.getKey();
            int lenLeft = diagLenLeft(key, diag);
            if (lenLeft == lenDiag) {
                candidate.getValue().incrementAndGet();
                return;
            }
            int lenRight = diagLenRight(key, diag);
            int lenTotal = lenLeft + lenRight;
            if (lenTotal > lenTotalTarget ||
                lenTotal == lenTotalTarget && lenRight > lenRightTarget) { // Prioritize longer trailing match (failure reason)
                lenLeftTarget = lenLeft;
                lenRightTarget = lenRight;
                lenTotalTarget = lenTotal;
                target = key;
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

    static class IgnoreQueues {

        private final Pattern pattern;
        private final HashSet<String> ignored;

        public IgnoreQueues(Set<String> patterns) {
            String regex = "(" + patterns.stream()
                           .map(IgnoreQueues::regexOf)
                           .collect(Collectors.joining("|")) + ")";
            log.debug("ignore regex is: {}", regex);
            this.pattern = Pattern.compile(regex);
            this.ignored = new HashSet<>();
        }

        boolean keepOrIgnore(String queue) {
            boolean match = pattern.matcher(queue).matches();
            if (match)
                ignored.add(queue);
            return !match;
        }

        public HashSet<String> getIgnored() {
            return ignored;
        }

        private static String regexOf(String pattern) {
            try {
                StringBuilder sb = new StringBuilder();

                StringReader r = new StringReader(pattern);
                for (;;) {
                    r.mark(1);
                    int c = r.read();
                    switch (c) {
                        case -1:
                            return sb.toString();
                        case '?':
                            sb.append(".");
                            break;
                        case '*':
                            sb.append(".*");
                            break;
                        default:
                            r.reset();
                            sb.append(Pattern.quote(extractStringPart(r)));
                            break;
                    }
                }
            } catch (IOException ex) {
                log.error("Error building regexp from: {}: {}", pattern, ex.getMessage());
                log.debug("Error building regexp from: {}: ", pattern, ex);
            }
            return "";
        }

        private static String extractStringPart(StringReader r) throws IOException {
            StringWriter w = new StringWriter();
            for (;;) {
                r.mark(1);
                int c = r.read();
                switch (c) {
                    case -1:
                        return w.toString();
                    case '?':
                    case '*':
                        r.reset();
                        return w.toString();
                    case '\\':
                        c = r.read();
                        if (c == -1)
                            return w.toString();
                        w.append((char) c);
                        break;
                    default:
                        w.append((char) c);
                        break;
                }
            }
        }
    }

    private Response jsonResponse(JsonStringProducer producer, String errorMessagePrefix) {
        try {
            return Response.ok()
                    .entity(producer.get())
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .header("Access-Control-Allow-Origin", "*")
                    .build();
        } catch (InterruptedException | ExecutionException ex) {
            log.error("{}: {}", errorMessagePrefix, ex.getMessage());
            log.debug("{}: ", errorMessagePrefix, ex);
            return Response.status(Response.Status.REQUEST_TIMEOUT).build();
        } catch (JsonProcessingException ex) {
            log.error("{}: {}", errorMessagePrefix, ex.getMessage());
            log.debug("{}: ", errorMessagePrefix, ex);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @FunctionalInterface
    private interface JsonStringProducer {

        String get() throws InterruptedException, ExecutionException, JsonProcessingException;
    }
}
