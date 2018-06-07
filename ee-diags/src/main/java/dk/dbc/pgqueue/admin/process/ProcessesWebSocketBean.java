package dk.dbc.pgqueue.admin.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.dbc.pgqueue.diags.QueueStatusBean;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
@Singleton
@ServerEndpoint("/queue-admin/processes")
@Lock(LockType.READ)
public class ProcessesWebSocketBean {

    private static final ObjectMapper O = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(ProcessesWebSocketBean.class);

    private static final int DIAG_MAX_CACHE_AGE = 45;
    private static final int DIAG_PERCENT_MATCH = 90;
    private static final int DIAG_COLLAPSE_MAX_ROWS = 12500;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private final ConcurrentHashMap<String, Session> sessions;
    private final ConcurrentHashMap<String, String> logReciever; // SessionId -> ProcessId

    @EJB
    Processes processes;

    @EJB
    QueueStatusBean qsb;

    private DataSource dataSource;

    private JobLogMapper mapper;

    public ProcessesWebSocketBean() {
        this.sessions = new ConcurrentHashMap<>();
        this.logReciever = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Queue WebSocket");

        String jndiName = readNameFromResource("pq-queue-admin.jndi-name");
        String logMapperName = readNameFromResource("pq-queue-admin.log-mapper");
        dataSource = lookupDataSource(jndiName);
        mapper = findMapper(logMapperName);
    }

    @PreDestroy
    public void destroy() {
        for (Session session : sessions.values()) {
            try {
                session.close();
            } catch (IOException e) {
                log.error("Error closing ws: {}", e.getMessage());
                log.debug("Error closing ws: ", e);
            }
        }
    }

    JobLogMapper findMapper(String name) throws SecurityException, EJBException {
        try {
            int idx = name.lastIndexOf(".");
            if (idx < 0) {
                throw new EJBException("Invalid mapper name " + name + " expected class.STATIC_VARIABLE");
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
            throw new EJBException("Cannot look up job mapper: " + name + " not of type  `static final LogMapper'");
        } catch (NoSuchFieldException | ClassNotFoundException ex) {
            throw new EJBException("Cannot look up job mapper: " + name, ex);
        } catch (IllegalArgumentException | IllegalAccessException ex) {
            throw new EJBException("Cannot access field: " + name, ex);
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
            throw new EJBException("Cannot look up: " + jndiName, ex);
        }
    }

    String readNameFromResource(String resource) throws EJBException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("/" + resource)) {
            if (is == null) {
                throw new EJBException("Cannot find `" + resource + "' resource");
            }
            byte[] bytes = new byte[1024];
            int len = is.read(bytes);
            String name = new String(bytes, 0, len, StandardCharsets.UTF_8).trim();
            if (name.isEmpty()) {
                throw new EJBException("Resource `" + resource + "' is empty");
            }
            log.info("Got name: {} from: {}", name, resource);
            return name;
        } catch (IOException ex) {
            throw new EJBException("Cannot read `" + resource + "' resource", ex);
        }
    }

    @OnOpen
    public void open(Session session) {
        sessions.put(session.getId(), session);
        ArrayList<Process> processList = new ArrayList<>(processes.allProcesses());
        // Sort by started timestamp
        Collections.sort(processList, (Process l, Process r) -> {
                     Instant ls = l.getStarted() == null ? Instant.now() : l.getStarted();
                     Instant rs = r.getStarted() == null ? Instant.now() : r.getStarted();
                     return ls.compareTo(rs);
                 });
        for (Process process : processList) {
            String message = buildProcessMessage(process, "update");
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException ex) {
                sessions.remove(session.getId());
                log.error("Error sending message: {}", ex.getMessage());
                log.debug("Error sending message: ", ex);
                return;
            }
        }
    }

    @OnClose
    public void close(Session session) {
        sessions.remove(session.getId());
        logReciever.remove(session.getId());
    }

    @OnError
    public void error(Throwable error) {
        log.error("Web socket error:", error);
    }

    @OnMessage
    public void message(String message, Session session) {
        try {
            JsonNode tree = O.readTree(message);
            if (tree.has("action")) {
                String action = tree.get("action").asText("").toLowerCase();
                log.trace("action = {}", action);
                switch (action) {
                    case "log": {
                        String id = tree.get("id").asText("");
                        logReciever.put(session.getId(), id);
                        break;
                    }
                    case "cancel": {
                        String id = tree.get("id").asText("");
                        Process process = processes.lookup(id);
                        if (process != null && process.isAlive()) {
                            process.cancel();
                        }
                        break;
                    }
                    case "purge": {
                        String id = tree.get("id").asText("");
                        Process process = processes.lookup(id);
                        if (process != null && process.isCompleted()) {
                            processes.prune(id);
                        }
                        break;
                    }
                    case "full-log": {
                        String id = tree.get("id").asText("");
                        Process process = processes.lookup(id);
                        if (process != null && process.isCompleted()) {
                            ProcessLogger logger = process.getLogger();

                            try (OutputStream out = session.getBasicRemote().getSendStream() ;
                                 InputStream in = logger.getLogFile()) {
                                byte[] buffer = new byte[1024];
                                for (;;) {
                                    int len = in.read(buffer);
                                    if (len <= 0) {
                                        break;
                                    }
                                    out.write(buffer, 0, len);
                                }
                            }
                        }
                        break;
                    }
                    case "queue-diags": {
                        String response = qsb.queueStatusText(dataSource, DIAG_PERCENT_MATCH, DIAG_COLLAPSE_MAX_ROWS, DIAG_MAX_CACHE_AGE, Collections.EMPTY_SET, true);
                        response = "{\"action\":\"queue_diags\"," + response.substring(1);
                        session.getBasicRemote().sendText(response);
                        break;
                    }
                    case "requeue": {
                        String pattern = tree.get("pattern").asText("");
                        Process process = createJob(pattern, "Requeue", "SELECT * FROM pgqueue_admin_requeue(?)");
                        String id = processes.registerProcess(process);
                        logReciever.put(session.getId(), id);
                        String response = buildProcessMessage(process, "add");
                        session.getBasicRemote().sendText(response);
                        processes.startProcess(id); // Ensure first log line comes after process started
                        break;
                    }
                    case "list": {
                        String pattern = tree.get("pattern").asText("");
                        Process process = createJob(pattern, "List", "SELECT * FROM queue_error WHERE diag LIKE ?");
                        String id = processes.registerProcess(process);
                        logReciever.put(session.getId(), id);
                        String response = buildProcessMessage(process, "add");
                        session.getBasicRemote().sendText(response);
                        processes.startProcess(id); // Ensure first log line comes after process started
                        break;
                    }
                    case "discard": {
                        String pattern = tree.get("pattern").asText("");
                        Process process = createJob(pattern, "Discard", "SELECT * FROM pgqueue_admin_discard(?)");
                        String id = processes.registerProcess(process);
                        logReciever.put(session.getId(), id);
                        String response = buildProcessMessage(process, "add");
                        session.getBasicRemote().sendText(response);
                        processes.startProcess(id); // Ensure first log line comes after process started
                        break;
                    }
                    default: {
                        log.warn("Unknown action: " + action);
                        break;
                    }
                }
            }
        } catch (InterruptedException | ExecutionException | IOException ex) {
            log.error("Error parsing content ({}): {}", message, ex.getMessage());
            log.debug("Error parsing content ({}): ", message, ex);
            try {
                session.close();
            } catch (IOException sessionEx) {
                log.error("Error closing session after exception: {}", sessionEx.getMessage());
                log.debug("Error closing session after exception: ", sessionEx);
            }
        }
    }

    private Process createJob(String pattern, String name, String sql) {
        return new Process(name) {
            @Override
            public void run(Logger log) {
                log.info("{} pattern: `{}'", name, pattern);
                int maxComsumerLength = 0;
                try (Connection connection = dataSource.getConnection() ;
                     PreparedStatement stmt = connection.prepareStatement(sql)) {
                    connection.setAutoCommit(false);
                    stmt.setString(1, pattern.replace("%", "\\%").replace("*", "%"));
                    try (ResultSet resultSet = stmt.executeQuery()) {
                        int row = 0;
                        if (resultSet.next()) {
                            do {
                                row++;
                                if (row % 1000 == 0) {
                                    log.info("{} rows", row);
                                }
                                String consumer = resultSet.getString("consumer");
                                maxComsumerLength = Integer.max(maxComsumerLength, consumer.length());
                                consumer = String.format(Locale.ROOT, "%-" + maxComsumerLength + "s", consumer);
                                log.info("{}: {} - @{}/{} {}", consumer,
                                         mapper.format(resultSet),
                                         resultSet.getTimestamp("queued")
                                                 .toLocalDateTime()
                                                 .format(DATE_TIME_FORMATTER),
                                         resultSet.getTimestamp("failedAt")
                                                 .toLocalDateTime()
                                                 .format(DATE_TIME_FORMATTER),
                                         resultSet.getString("diag"));
                            } while (resultSet.next());
                            if (row % 1000 != 0) {
                                log.info("{} rows", row);
                            }
                            log.info("Committing");
                            connection.commit();
                        } else {
                            log.info("No rows matched");
                        }
                    } catch (SQLException ex) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackEx) {
                            log.error("Error rollong back transaction: {}", rollbackEx.getMessage());
                            log.debug("Error rollong back transaction: ", rollbackEx);
                        }
                        throw ex;
                    }
                } catch (SQLException ex) {
                    log.error("Error from database: {}", ex.getMessage());
                    log.debug("Error from database: ", ex);
                }
            }

        };
    }

    public void broadcastLog(String id, String message) {
        String text = buildLogMessage(id, message);

        for (Iterator<Map.Entry<String, String>> iterator = logReciever.entrySet().iterator() ; iterator.hasNext() ;) {
            Map.Entry<String, String> pair = iterator.next();
            if (pair.getValue().equals(id)) {
                Session session = sessions.get(pair.getKey());
                try {
                    session.getBasicRemote().sendText(text);
                } catch (IOException ex) {
                    iterator.remove();
                    sessions.remove(session.getId());
                    log.error("Error sending message: {}", ex.getMessage());
                    log.debug("Error sending message: ", ex);
                }
            }
        }
    }

    public void broadcastUpdate(Process process) {
        broadcast(buildProcessMessage(process, "update"));
    }

    public void broadcastGone(Process process) {
        broadcast(buildProcessMessage(process, "remove"));
    }

    private void broadcast(String message) {
        for (Iterator<Session> iterator = sessions.values().iterator() ; iterator.hasNext() ;) {
            Session session = iterator.next();
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException ex) {
                iterator.remove();
                logReciever.remove(session.getId());
                log.error("Error sending message: {}", ex.getMessage());
                log.debug("Error sending message: ", ex);
            }
        }
    }

    private static String buildProcessMessage(Process process, String action) {
        try {
            ObjectNode o = O.createObjectNode();
            o.put("action", action);
            o.put("id", process.getProcessId());
            o.put("name", process.getProcessName());
            boolean running = process.isRunning();
            o.put("running", running);
            boolean alive = process.isAlive();
            o.put("alive", alive);
            boolean completed = process.isCompleted();
            o.put("completed", completed);
            if (running || completed) {
                o.put("started", process.getStarted().toString());
            }
            if (completed) {
                o.put("stopped", process.getStopped().toString());
            }
            return O.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            log.error("Error building json: {}", ex.getMessage());
            log.debug("Error building json: ", ex);
            return "";
        }
    }

    private static String buildLogMessage(String id, String message) {
        try {
            ObjectNode o = O.createObjectNode();
            o.put("action", "log");
            o.put("id", id);
            o.put("message", message);
            return O.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            log.error("Error building json: {}", ex.getMessage());
            log.debug("Error building json: ", ex);
            return "";
        }
    }

}
