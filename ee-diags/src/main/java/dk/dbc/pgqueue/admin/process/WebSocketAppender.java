package dk.dbc.pgqueue.admin.process;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.Layout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
class WebSocketAppender extends AppenderBase<ILoggingEvent> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAppender.class);

    private final String id;
    private final Layout<ILoggingEvent> layout;
    private final ProcessesWebSocketBean webSocket;

    public WebSocketAppender(String id, PatternLayoutEncoder encoder, ProcessesWebSocketBean webSocket) {
        this.id = id;
        this.layout = encoder.getLayout();
        this.webSocket = webSocket;
    }

    @Override
    protected void append(ILoggingEvent event) {
        webSocket.broadcastLog(id, layout.doLayout(event));
    }

}
