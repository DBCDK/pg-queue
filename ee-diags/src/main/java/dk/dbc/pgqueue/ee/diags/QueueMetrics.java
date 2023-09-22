package dk.dbc.pgqueue.ee.diags;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.Gauge;
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

    @Inject
    public QueueStatusBean bean;

    @EJB
    public PgQueueAdminConfig config;

    private QueueStatusBean.IgnoreQueues ignoreQueues;
    private HashMap<String, QueueGauge> gauges;
    private long errors;

    @PostConstruct
    public void init() {
        log.debug("ignoreRules (for metrics) = {}", ignoreRules);
        ignoreQueues = new QueueStatusBean.IgnoreQueues(
                Arrays.stream(ignoreRules.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet()));
        gauges = new HashMap<>();
        registry.register(ERRORS, new Gauge<Long>() {
                      @Override
                      public Long getValue() {
                          return errors;
                      }
                  });
    }

    @Schedule(hour = "*", minute = "*")
    public synchronized void looper() {
        log.debug("metrics");

        try {
            ObjectNode queueStatus = bean.queueStatus(config.getDataSource(),
                                                      config.getDiagPercentMatch(),
                                                      config.getDiagCollapseMaxRows(),
                                                      config.getMaxCacheAge(),
                                                      Collections.EMPTY_SET,
                                                      false);
            JsonNode queues = queueStatus.get("queue");
            errors = queueStatus.get("diag-count").asLong();
            if (queues.isObject()) { // Not an SQL error
                HashMap<String, QueueGauge> resetGauges = new HashMap<>(gauges);
                queues.fields().forEachRemaining(e -> {
                    String queue = e.getKey();
                    if (ignoreQueues.keepOrIgnore(queue)) {
                        gauges.computeIfAbsent(queue, QueueGauge::new)
                                .update(e.getValue());
                        resetGauges.remove(queue);
                    }
                });
                resetGauges.values().forEach(QueueGauge::reset);
            }
        } catch (ExecutionException | InterruptedException ex) {
            log.error("Error getting queue status for metrics: {}", ex.getMessage());
            log.debug("Error getting queue status for metrics: ", ex);
        }
    }

    private class QueueGauge {

        private long age;
        private long count;

        public QueueGauge(String queue) {
            Tag tag = new Tag("queue", queue);
            registry.gauge(new MetricID(AGE, tag), () -> age);
            registry.gauge(new MetricID(COUNT, tag), () -> count);
        }

        public void update(JsonNode node) {
            age = node.get("age").asLong();
            count = node.get("count").asLong();
        }

        public void reset() {
            age = 0;
            count = 0;
        }
    }
}
