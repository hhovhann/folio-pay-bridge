package dev.foliopay.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The audit trail (PCI DSS v4.0.1 10.2, D-43): who did what to which resource, when, with what outcome, from where. One line per event on
 * its own logger, {@value #LOGGER}, so a log pipeline can route it to storage kept for a year. It is held at INFO whatever the
 * application's level, and the application refuses to start if it is switched off ({@code AuditTrail}). The time is the log line's own and
 * the request id comes from the log context. Values are ids, never secrets: a card is named by a one-way fingerprint, never by its token,
 * and every line passes through {@link LogRedactor} like any other.
 *
 * <pre>{@code AuditLog.event(Event.CARD_OPENED).resource("card:fp:" + fingerprint).success();}</pre>
 */
public final class AuditLog {
  public static final String LOGGER = "dev.foliopay.audit";
  /** Who acted outside any request: the scheduled relay, retries and purges. */
  static final String SYSTEM = "system";
  /** Who acted in a request that carried no authenticated client (a local demo with authentication off). */
  static final String ANONYMOUS = "anonymous";
  private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER);

  /** What happened. The PCI DSS v4.0.1 requirement each event answers is on its constant. */
  public enum Event {
    /** 10.2.1.6: the trail started. */
    AUDIT_STARTED,
    /** 10.2.1.6: the trail stopped (the application shut down). */
    AUDIT_STOPPED,
    /** 10.2.1.5, 10.2.1.7: the payment and authentication configuration in force, at every start (a change is a deploy). */
    CONFIGURATION_LOADED,
    /** 10.2.1.4: a call with missing, unknown or expired credentials. */
    AUTHENTICATION_FAILED,
    /** 10.2.1.4: an authenticated client asked for an operation its scopes do not allow. */
    ACCESS_DENIED,
    /** 10.2.1.2: any call that changes state, operators' replays, redeliveries and reconciliations included. */
    API_CALL,
    /** 10.2.1.1: a card number was sealed into the vault. */
    CARD_STORED,
    /** 10.2.1.1: a card number was opened for an authorisation. */
    CARD_OPENED,
    /** 3.2.1: a card number was destroyed at the end of its retention period. */
    CARD_PURGED,
    PAYMENT_CAPTURED,
    PAYMENT_DECLINED,
    /** The processor refused the request itself: ours to fix, not the guest's card. */
    PAYMENT_REFUSED
  }

  private AuditLog() {}

  public static Entry event(Event event) {
    return new Entry(event);
  }

  /** Whether the trail is on: the application refuses to start when it is not. */
  public static boolean isEnabled() {
    return AUDIT.isInfoEnabled();
  }

  /** One event being described; nothing is written until {@link #success()} or {@link #failure()}. */
  public static final class Entry {
    private final Event event;
    private String client = MDC.get("client");
    private String resource;
    private final StringBuilder details = new StringBuilder();

    private Entry(Event event) {
      this.event = event;
    }

    /** The acting client, where it is not in the log context (a refusal is decided after the request's own context is gone). */
    public Entry client(String name) {
      this.client = name;
      return this;
    }

    /** What was acted on: {@code charge:ch_1}, {@code card:fp:...}, a request path. */
    public Entry resource(String name) {
      this.resource = name;
      return this;
    }

    public Entry detail(String name, Object value) {
      details.append(' ').append(name).append('=').append(oneToken(value == null ? null : value.toString()));
      return this;
    }

    public void success() {
      write("SUCCESS");
    }

    public void failure() {
      write("FAILURE");
    }

    private void write(String outcome) {
      AUDIT.info("event={} outcome={} client={} resource={}{}", event, outcome, actor(), oneToken(resource), details);
    }

    private String actor() {
      if (client != null && !client.isBlank()) {
        return oneToken(client);
      }
      return MDC.get("requestId") == null ? SYSTEM : ANONYMOUS;
    }
  }

  /** A value with a space or a line break cannot start a field or a line of its own: {@code a outcome=SUCCESS} stays one value. */
  static String oneToken(String value) {
    return value == null || value.isBlank() ? "-" : value.strip().replaceAll("\\s+", "_");
  }
}
