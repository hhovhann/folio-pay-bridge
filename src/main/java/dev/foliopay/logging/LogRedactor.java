package dev.foliopay.logging;

import java.util.regex.Pattern;

/**
 * The one place text is made safe before it leaves the process: every log format, the console's recent lines and the error codes read
 * from OPERA and the processor pass through it (D-41). The code never logs a secret; this catches what slips through, so it over-masks
 * rather than under-masks. Key material without a name around it cannot be recognised in text: types that hold one print a redacted
 * {@code toString()} instead ({@code SensitiveTypesTest}).
 */
public final class LogRedactor {
  static final String REDACTED = "[redacted]";
  private static final String NOT_WORD_BEFORE = "(?<![A-Za-z0-9])";
  private static final String NOT_WORD_AFTER = "(?![A-Za-z0-9])";
  private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
  private static final Pattern SCHEME_CREDENTIAL = Pattern.compile("(?i)" + NOT_WORD_BEFORE + "(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{16,}");
  private static final Pattern API_KEY = Pattern.compile(NOT_WORD_BEFORE + "fpb_(live|test)_[A-Za-z0-9]{8,}");
  private static final Pattern CARD_TOKEN = Pattern.compile(NOT_WORD_BEFORE + "tok_[0-9a-f]{32}" + NOT_WORD_AFTER);
  private static final Pattern SECRET_FIELD = Pattern.compile("(?i)(" + NOT_WORD_BEFORE + "[\"']?(?:"
      + "password|passwd|pwd|secret|client[_-]?secret|access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|x-api-key|app[_-]?key"
      + "|authorization|private[_-]?key|data[_-]?key|cvv2?|cvc2?|cav2|cid|card[_-]?verification[_-]?(?:code|value)|security[_-]?code|pin"
      + ")" + NOT_WORD_AFTER + "[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\]\"']+)");

  private LogRedactor() {}

  /** Masks card numbers, card tokens, signed tokens, credentials, API keys and the value of any field named like a secret. */
  public static String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String masked = JWT.matcher(text).replaceAll(REDACTED);
    masked = SCHEME_CREDENTIAL.matcher(masked).replaceAll("$1 " + REDACTED);
    masked = API_KEY.matcher(masked).replaceAll("fpb_$1_" + REDACTED);
    masked = CARD_TOKEN.matcher(masked).replaceAll("tok_" + REDACTED);
    masked = SECRET_FIELD.matcher(masked).replaceAll("$1" + REDACTED);
    return PanMasker.mask(masked);
  }

  /** {@link #redact}, then one line: a value with a line break cannot forge a second log entry. */
  public static String safe(String text) {
    String redacted = redact(text);
    return redacted == null ? null : redacted.replace("\r", "\\r").replace("\n", "\\n");
  }
}
