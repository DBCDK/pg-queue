package dk.dbc.pgqueue.admin.process;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
class WebSocketAppender extends AppenderBase<ILoggingEvent> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAppender.class);

    private final String id;
    private final PatternLayoutEncoder encoder;
    private final ProcessesWebSocketBean webSocket;

    public WebSocketAppender(String id, PatternLayoutEncoder encoder, ProcessesWebSocketBean webSocket) {
        this.id = id;
        this.encoder = encoder;
        this.webSocket = webSocket;
    }

    @Override
    protected void append(ILoggingEvent event) {
        String message = new String(encoder.encode(event), StandardCharsets.UTF_8);
        webSocket.broadcastLog(id, message);
    }

}
