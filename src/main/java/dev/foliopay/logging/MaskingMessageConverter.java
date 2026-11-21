package dev.foliopay.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Replaces %msg in the log pattern so every appender prints redacted, single-line text. */
public class MaskingMessageConverter extends MessageConverter {
  @Override
  public String convert(ILoggingEvent event) { return LogRedactor.safe(super.convert(event)); }
}
