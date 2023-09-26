package dk.dbc.pgqueue.ee.diags;

import com.hazelcast.map.IMap;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
@Singleton
@Startup
public class QueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    private static final String COUNT = "queue_count";
    private static final String AGE = "queue_age";
    private static final String ERRORS = "queue_errors";

    @ConfigProperty(name = "IGNORE_QUEUES", defaultValue = "*-slow")
    @Inject
    public String ignoreRules;

    @Inject
    public MetricRegistry registry;

    @EJB
    public PgQueueAdminConfig config;

    @Inject
    public IMap<String, Instant> lastSeen;

    private QueueStatusBean.IgnoreQueues ignoreQueues;
    private HashMap<String, QueueGauge> gauges;

    @PostConstruct
    public void init() {
        log.debug("ignoreRules (for metrics) = {}", ignoreRules);
        ignoreQueues = new QueueStatusBean.IgnoreQueues(
                Arrays.stream(ignoreRules.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet()));
        gauges = new HashMap<>();
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        lastSeen.entrySet().stream()
                .filter(e -> e.getValue().isAfter(cutoff))
                .map(Map.Entry::getKey)
                .filter(ignoreQueues::keepOrIgnore)
                .forEach(queue -> gauges.computeIfAbsent(queue, QueueGauge::new));
        log.debug("(initial) gauges = {}", gauges);
    }

    @Schedule(hour = "*", minute = "*")
    public synchronized void looper() {
        log.debug("metrics");

        try (Connection connection = config.getDataSource().getConnection();
             Statement stmt = connection.createStatement()) {
            Instant loopTs = Instant.now();
            HashSet<String> clearQueues = new HashSet(gauges.keySet());

            try (ResultSet resultSet = stmt.executeQuery("SELECT consumer, COUNT(*), EXTRACT(EPOCH FROM NOW() - MIN(dequeueafter)) FROM queue WHERE dequeueafter < NOW() GROUP BY consumer")) {
                while (resultSet.next()) {
                    int i = 0;
                    String queue = resultSet.getString(++i);
                    log.debug("queue = {}", queue);
                    if (ignoreQueues.keepOrIgnore(queue)) {
                        clearQueues.remove(queue);
                        lastSeen.put(queue, loopTs);
                        int count = resultSet.getInt(++i);
                        double age = resultSet.getDouble(++i);
                        gauges.computeIfAbsent(queue, QueueGauge::new)
                                .withAge(age)
                                .withCount(count);
                    }
                }
            }
            clearQueues.forEach(queue -> gauges.get(queue)
                    .withAge(0)
                    .withCount(0));

            HashSet<String> clearErrors = new HashSet(gauges.keySet());
            try (ResultSet resultSet = stmt.executeQuery("SELECT consumer, COUNT(*) FROM queue_error GROUP BY consumer")) {
                while (resultSet.next()) {
                    int i = 0;
                    String queue = resultSet.getString(++i);
                    log.debug("error_queue = {}", queue);
                    if (ignoreQueues.keepOrIgnore(queue)) {
                        clearErrors.remove(queue);
                        lastSeen.put(queue, loopTs);
                        int count = resultSet.getInt(++i);
                        gauges.computeIfAbsent(queue, QueueGauge::new)
                                .withErrors(count);
                    }
                }
            }
            clearErrors.forEach(queue -> gauges.get(queue)
                    .withErrors(0));

        } catch (SQLException ex) {
            log.error("Error accessing queue tables: {}", ex.getMessage());
            log.debug("Error accessing queue tables: ", ex);
        }
    }

    private class QueueGauge {

        private double age;
        private long count;
        private long errors;

        public QueueGauge(String queue) {
            Tag tag = new Tag("queue", queue);
            registry.gauge(new MetricID(AGE, tag), () -> age);
            registry.gauge(new MetricID(COUNT, tag), () -> count);
            registry.gauge(new MetricID(ERRORS, tag), () -> errors);
        }

        public QueueGauge withAge(double age) {
            this.age = age;
            return this;
        }

        public QueueGauge withCount(long count) {
            this.count = count;
            return this;
        }

        public QueueGauge withErrors(long errors) {
            this.errors = errors;
            return this;
        }

        @Override
        public String toString() {
            return "QueueGauge{" + "age=" + age + ", count=" + count + ", errors=" + errors + '}';
        }
    }
}
