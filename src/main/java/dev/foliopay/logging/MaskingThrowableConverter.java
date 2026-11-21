package dev.foliopay.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Exception messages and stack traces are redacted too: an exception built from request data must not carry a secret into the logs. */
public class MaskingThrowableConverter extends ThrowableProxyConverter {
  @Override
  public String convert(ILoggingEvent event) { return LogRedactor.redact(super.convert(event)); }
}
