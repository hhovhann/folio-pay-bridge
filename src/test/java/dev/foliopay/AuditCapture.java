package dev.foliopay;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.foliopay.logging.AuditLog;
import java.util.List;
import org.slf4j.LoggerFactory;

/** The audit lines written while it is open, as the raw formatted messages (before any masking), for a test to read. */
public final class AuditCapture implements AutoCloseable {
  private final Logger logger = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  public AuditCapture() {
    appender.start();
    logger.addAppender(appender);
  }

  public List<String> lines() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** The lines of one event type, in the order they were written. */
  public List<String> of(AuditLog.Event event) {
    return lines().stream().filter(line -> line.startsWith("event=" + event + " ")).toList();
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
  }
}
