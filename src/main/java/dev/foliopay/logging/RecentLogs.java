package dev.foliopay.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Keeps the last log lines, already masked, so the demo console can show exactly what an operator would see. Exists only with the demo
 * controls on: production logs go to the console appender once, not to a second in-memory copy.
 */
@Component
@ConditionalOnProperty(name = "foliopay.demo-controls", havingValue = "true")
public class RecentLogs {
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);
    private final Deque<String> lines = new ArrayDeque<>();
    private AppenderBase<ILoggingEvent> appender;

    @PostConstruct
    void attach() {
        if (!(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) instanceof Logger root)) return;
        AppenderBase<ILoggingEvent> app = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent e) {
                if (!e.getLoggerName().startsWith("dev.foliopay")) return;
                add(F.format(Instant.ofEpochMilli(e.getTimeStamp())) + " " + e.getLevel() + " " + shortName(e.getLoggerName()) + " - " + PanMasker.safe(e.getFormattedMessage()));
            }
        };
        app.setContext(root.getLoggerContext());
        app.setName("RECENT");
        app.start();
        root.addAppender(app);
        appender = app;
    }

    /** A context that is closed (a test, a restart in the same JVM) must not leave its appender on the shared root logger. */
    @PreDestroy
    void detach() {
        if (appender != null && LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) instanceof Logger root) {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    private static String shortName(String n) { return n.substring(n.lastIndexOf('.') + 1); }

    public synchronized void add(String line) {
        lines.addLast(line);
        while (lines.size() > 200) lines.removeFirst();
    }

    public synchronized List<String> tail(int n) {
        List<String> all = new ArrayList<>(lines);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    public synchronized void clear() { lines.clear(); }
}
