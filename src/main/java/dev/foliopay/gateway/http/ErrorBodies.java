package dev.foliopay.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.foliopay.domain.rule.SafeText;

/** Reads the error code out of a downstream error body. A body that is not JSON, or has no code, gives a fixed answer instead of an exception. */
final class ErrorBodies {
  static final String NO_REASON = "no reason given";
  private static final ObjectMapper JSON = new ObjectMapper();

  private ErrorBodies() {}

  /** The {@code code} field, cleaned to one bounded line (it came from outside), or {@link #NO_REASON}. */
  static String code(String body) {
    if (body == null || body.isBlank()) {
      return NO_REASON;
    }
    try {
      JsonNode code = JSON.readTree(body).path("code");
      return code.isTextual() && !code.asText().isBlank() ? SafeText.clean(code.asText()) : NO_REASON;
    } catch (Exception unreadable) { // a truncated or non-JSON body (a proxy's HTML page) is not an error of ours
      return NO_REASON;
    }
  }
}
