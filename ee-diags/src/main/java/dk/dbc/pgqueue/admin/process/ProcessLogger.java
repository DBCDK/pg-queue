package dk.dbc.pgqueue.admin.process;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
class ProcessLogger {

    private static final Logger log = LoggerFactory.getLogger(ProcessLogger.class);

    private final String id;
    private final File file;
    private final ch.qos.logback.classic.Logger logger;
    private final OutputStreamAppender fileAppender;
    private WebSocketAppender wsAppender;
    private final LoggerContext context;
    private final PatternLayoutEncoder encoder;
    private final FileOutputStream write;

    ProcessLogger(String id) throws IOException {
        this(id, Level.INFO);
    }

    ProcessLogger(String id, Level logLevel) throws IOException {
        this.id = id;
        this.file = new File("/tmp/async-" + id.replaceAll("[^-_0-9a-zA-Z]", "_") + ".log");
        if (this.file.delete()) {
            log.warn("removed old log file when starting new, strange ({})", this.file.getAbsolutePath());
        }
        this. write = new FileOutputStream(file);
        this.context = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.logger = context.getLogger(id);
        logger.setLevel(logLevel);
        logger.setAdditive(false);

        this.encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{YYYY-MM-dd HH:mm:ss:SSS} %-5level{} %msg%n%rEx");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        this.fileAppender = new OutputStreamAppender();
        fileAppender.setName(id + "-file");
        fileAppender.setContext(logger.getLoggerContext());
        fileAppender.setOutputStream(write);
        fileAppender.setEncoder(encoder);
        fileAppender.start();
        logger.addAppender(fileAppender);

    }

    @Override
    @SuppressWarnings("FinalizeDeclaration")
    protected void finalize() throws Throwable {
        super.finalize();
        try {
            write.close();
        } catch (IOException ex) {
            log.error("Error closing output file: {}", ex.getMessage());
            log.debug("Error closing output file: ", ex);
        }
        if (!file.delete()) {
            log.warn("could not remove log file. strange ({})", file.getAbsolutePath());
        }
    }

    ch.qos.logback.classic.Logger getLog() {
        return logger;
    }

    void stop() {
        logger.detachAndStopAllAppenders();
    }

    InputStream getLogFile() {
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException ex) {
            return new ByteArrayInputStream("LOGFILE NOT FOUND".getBytes(StandardCharsets.UTF_8));
        }
    }

    void setWebSocket(ProcessesWebSocketBean webSocket) {
        wsAppender = new WebSocketAppender(id, encoder, webSocket);
        wsAppender.setName(id + "-ws");
        wsAppender.setContext(context);
        wsAppender.start();
        logger.addAppender(wsAppender);
    }

}
