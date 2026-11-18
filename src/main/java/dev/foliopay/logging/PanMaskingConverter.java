package dev.foliopay.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Replaces %msg in the log pattern so every appender prints masked, single-line text. */
public class PanMaskingConverter extends MessageConverter {
  @Override
  public String convert(ILoggingEvent event) { return PanMasker.safe(super.convert(event)); }
}
