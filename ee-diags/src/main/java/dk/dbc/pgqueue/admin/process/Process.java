package dk.dbc.pgqueue.admin.process;

import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public abstract class Process {

    private static final Logger log = LoggerFactory.getLogger(Process.class);

    private final String processName;
    private String processId;
    private boolean alive;
    private boolean running;
    private boolean completed;
    private Instant started;
    private Instant stopped;
    private ProcessLogger logger;
    private Thread thread;

    private ProcessesWebSocketBean webSocket;

    public Process(String processName) {
        this.processName = processName;
        this.processId = null;
        this.alive = this.running = this.completed = false;
        this.thread = null;
        this.webSocket = null;
    }

    public String getProcessName() {
        return processName;
    }

    public String getProcessId() {
        return processId;
    }

    /**
     * This sets the process id, and starts the logger
     *
     * @param processId uuid-style identifier
     * @return self for chaining
     */
    Process setProcessId(String processId) {
        this.processId = processId;
        try {
            this.logger = new ProcessLogger(processId);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return this;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAlive() {
        return alive;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void cancel() {
        if (alive) {
            thread.interrupt();
            alive = false;
            notifyState();
        }
    }

    public Instant getStarted() {
        return started;
    }

    public Instant getStopped() {
        return stopped;
    }

    ProcessLogger getLogger() {
        return logger;
    }

    /**
     * Initialize websocket log broadcaster
     * <p>
     * {@link #setProcessId(java.lang.String) } should be called before
     *
     * @param webSocket websocket bean
     */
    void setWebSocket(ProcessesWebSocketBean webSocket) {
        this.webSocket = webSocket;
        logger.setWebSocket(webSocket);
    }

    final void start() {
        started = Instant.now();
        running = alive = true;
        this.thread = Thread.currentThread();
        notifyState();
        try {
            run(logger.getLog());
        } catch (Exception ex) {
            log.error("Error processing task {} ({}): {}", processName, processId, ex.getMessage());
            log.debug("Error processing task {} ({}): ", processName, processId, ex);
        } finally {
            stopped = Instant.now();
            alive = running = false;
            completed = true;
            notifyState();
            logger.stop();
        }
    }

    private void notifyState() {
        if (webSocket != null) { // Unrealistic it is unset, but better safe than sorry
            webSocket.broadcastUpdate(this);
        }
    }

    public abstract void run(Logger log);

}
