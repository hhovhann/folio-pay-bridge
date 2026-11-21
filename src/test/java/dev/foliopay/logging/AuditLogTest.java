package dev.foliopay.logging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.AuditCapture;
import dev.foliopay.logging.AuditLog.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("Audit log: one line per event, with fields that cannot be forged")
class AuditLogTest {
  @Test
  @DisplayName("An event names its type, outcome, client, resource and details in that order")
  void anEventNamesItsTypeOutcomeClientResourceAndDetails() {
    MDC.put("client", "pms-1");
    try (AuditCapture audit = new AuditCapture()) {
      AuditLog.event(Event.PAYMENT_CAPTURED).resource("charge:ch_1").detail("amount", 5000).detail("currency", "USD").success();

      assertThat(audit.lines()).containsExactly("event=PAYMENT_CAPTURED outcome=SUCCESS client=pms-1 resource=charge:ch_1 amount=5000 currency=USD");
    } finally {
      MDC.remove("client");
    }
  }

  @Test
  @DisplayName("Without a client in the log context, the actor is the system")
  void withoutAClientTheActorIsTheSystem() {
    try (AuditCapture audit = new AuditCapture()) {
      AuditLog.event(Event.CARD_PURGED).resource("card:fp:0123").failure();

      assertThat(audit.lines()).containsExactly("event=CARD_PURGED outcome=FAILURE client=system resource=card:fp:0123");
    }
  }

  @Test
  @DisplayName("In a request without an authenticated client, the actor is anonymous, not the system")
  void inARequestWithoutAnAuthenticatedClientTheActorIsAnonymous() {
    MDC.put("requestId", "req-1");
    try (AuditCapture audit = new AuditCapture()) {
      AuditLog.event(Event.API_CALL).resource("/api/v1/payments").success();

      assertThat(audit.lines()).containsExactly("event=API_CALL outcome=SUCCESS client=anonymous resource=/api/v1/payments");
    } finally {
      MDC.remove("requestId");
    }
  }

  @Test
  @DisplayName("A value with spaces or line breaks stays one value and cannot forge a field or a line")
  void aValueWithSpacesOrLineBreaksStaysOneValue() {
    try (AuditCapture audit = new AuditCapture()) {
      AuditLog.event(Event.ACCESS_DENIED).client("evil outcome=SUCCESS").resource("/a\nevent=AUDIT_STOPPED").detail("reason", null).failure();

      assertThat(audit.lines()).singleElement().asString().doesNotContain("\n").doesNotContain(" outcome=SUCCESS")
          .contains("client=evil_outcome=SUCCESS", "resource=/a_event=AUDIT_STOPPED", "reason=-");
    }
  }

  @Test
  @DisplayName("The trail is on at the level the application ships with")
  void theTrailIsOnAtTheLevelTheApplicationShipsWith() {
    assertThat(AuditLog.isEnabled()).isTrue();
  }
}
