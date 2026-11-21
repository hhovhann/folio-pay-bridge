package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import dev.foliopay.AuditCapture;
import dev.foliopay.logging.AuditLog;
import dev.foliopay.logging.AuditLog.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("Audit trail lifecycle: it cannot be switched off, and it records its start, its stop and the configuration in force")
class AuditTrailTest {
  private static final String SECRET = "s3cr3t-value-never-logged";

  @Test
  @DisplayName("The start records the payment configuration by value and each secret only as set or not set")
  void theStartRecordsTheConfigurationByValueAndEachSecretOnlyAsSetOrNotSet() {
    MockEnvironment environment = new MockEnvironment().withProperty("foliopay.gateway", "http").withProperty("foliopay.opera.cashier-id", "9001")
        .withProperty("foliopay.vault.retention", "90d").withProperty("foliopay.http.opera-client-secret", SECRET)
        .withProperty("foliopay.http.psp-base-url", "https://user:" + SECRET + "@psp.example.test:8443/v1?key=" + SECRET)
        .withProperty("foliopay.auth.jwt.jwk-set-uri", "https://idp.example.test/tenant-1/.well-known/jwks.json");
    try (AuditCapture audit = new AuditCapture()) {
      new AuditTrail(environment).started();

      assertThat(audit.of(Event.AUDIT_STARTED)).hasSize(1);
      assertThat(audit.of(Event.CONFIGURATION_LOADED)).singleElement().asString()
          .contains("foliopay.gateway=http", "foliopay.opera.cashier-id=9001", "foliopay.vault.retention=90d")
          .contains("foliopay.http.opera-client-secret=set", "foliopay.webhook.secret=not-set")
          .contains("foliopay.http.psp-base-url=https://psp.example.test:8443")
          .as("whose signatures are trusted is configuration in force too").contains("foliopay.auth.jwt.jwk-set-uri=https://idp.example.test")
          .doesNotContain(SECRET);
    }
  }

  @Test
  @DisplayName("The stop is recorded")
  void theStopIsRecorded() {
    try (AuditCapture audit = new AuditCapture()) {
      new AuditTrail(new MockEnvironment()).stopped();
      assertThat(audit.of(Event.AUDIT_STOPPED)).hasSize(1);
    }
  }

  @Test
  @DisplayName("With its logger switched off, the trail refuses to let the application start")
  void withItsLoggerSwitchedOffTheTrailRefusesToLetTheApplicationStart() {
    Logger logger = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER);
    Level before = logger.getLevel();
    try {
      logger.setLevel(Level.WARN);
      assertThatThrownBy(() -> new AuditTrail(new MockEnvironment()).refuseToRunWithoutTheTrail()).hasMessageContaining(AuditLog.LOGGER);
      logger.setLevel(Level.INFO);
      assertThatCode(() -> new AuditTrail(new MockEnvironment()).refuseToRunWithoutTheTrail()).doesNotThrowAnyException();
    } finally {
      logger.setLevel(before);
    }
  }

  @Test
  @DisplayName("An address is recorded as scheme, host and port only, and an unreadable one says so")
  void anAddressIsRecordedAsSchemeHostAndPortOnly() {
    assertThat(AuditTrail.origin("https://opera.example.test/oauth?x=1")).isEqualTo("https://opera.example.test");
    assertThat(AuditTrail.origin("")).isEmpty();
    assertThat(AuditTrail.origin("http://bad host")).isEqualTo("unreadable");
  }
}
