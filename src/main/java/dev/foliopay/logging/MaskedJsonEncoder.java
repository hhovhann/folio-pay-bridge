package dev.foliopay.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One JSON object per line, for CloudWatch Logs Insights and any log pipeline that queries fields instead of grepping text
 * ({@code logback-json.xml}, chosen with {@code LOGGING_CONFIG=classpath:logback-json.xml}). The same rules as the text format: the message
 * and the stack trace are masked for card numbers (D-8), and a line break in a value cannot start a new entry (JSON escapes it). The
 * request id and the client from the log context are fields of their own, so one payment can be followed by a query.
 */
public class MaskedJsonEncoder extends EncoderBase<ILoggingEvent> {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ThrowableProxyConverter stackTrace = new ThrowableProxyConverter();

  @Override
  public void start() {
    stackTrace.setContext(getContext());
    stackTrace.start();
    super.start();
  }

  @Override
  public byte[] headerBytes() {
    return null;
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
    line.put("level", event.getLevel().toString());
    line.put("logger", event.getLoggerName());
    line.put("thread", event.getThreadName());
    Map<String, String> context = event.getMDCPropertyMap();
    if (context != null) {
      context.forEach((key, value) -> line.put(key, value)); // requestId, client: set by our filters from validated values
    }
    line.put("message", PanMasker.mask(event.getFormattedMessage()));
    if (event.getThrowableProxy() != null) {
      line.put("exception", PanMasker.mask(stackTrace.convert(event)));
    }
    try {
      return (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException impossible) { // a map of strings always serialises
      return ("{\"level\":\"ERROR\",\"message\":\"log line could not be encoded\"}\n").getBytes(StandardCharsets.UTF_8);
    }
  }

  @Override
  public byte[] footerBytes() {
    return null;
  }
}
