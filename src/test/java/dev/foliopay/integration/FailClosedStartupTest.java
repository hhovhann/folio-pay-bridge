package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import dev.foliopay.FolioPayApplication;
import dev.foliopay.logging.AuditLog;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The application must refuse to start rather than quietly run with a throwaway key or with fakes that pretend to move money. */
@DisplayName("Fail closed: the application refuses to start rather than run unsafely")
class FailClosedStartupTest {
  /** The hotel's own OPERA configuration, which every start needs; the values are the simulator's. */
  private static final List<String> HOTEL =
      List.of("foliopay.opera.hotel-id=DEMO1", "foliopay.opera.payment-method=CC", "foliopay.opera.cashier-id=9001");

  private ConfigurableApplicationContext start(String... properties) {
    return run(WebApplicationType.NONE, Stream.concat(HOTEL.stream(), Arrays.stream(properties)).toList());
  }

  private ConfigurableApplicationContext startWeb(String... properties) {
    return run(WebApplicationType.SERVLET, Stream.concat(HOTEL.stream(), Arrays.stream(properties)).toList());
  }

  private static ConfigurableApplicationContext run(WebApplicationType type, List<String> properties) {
    String[] args = properties.stream().map(p -> "--" + p).toArray(String[]::new); // command-line arguments outrank application.properties
    return new SpringApplicationBuilder(FolioPayApplication.class).web(type).run(args);
  }

  @Test
  @DisplayName("With no key mode, the application does not start")
  void withNoKeyModeItDoesNotStart() {
    assertThatThrownBy(() -> start("foliopay.keys=", "foliopay.gateway=fake")).hasStackTraceContaining("KeyService");
  }

  @Test
  @DisplayName("With no gateway, the application does not start instead of pretending to charge cards")
  void withNoGatewayItDoesNotStartInsteadOfPretendingToChargeCards() {
    assertThatThrownBy(() -> start("foliopay.keys=local", "foliopay.gateway=")).hasStackTraceContaining("PspClient");
  }

  @Test
  @DisplayName("With the demo settings, the application starts")
  void withTheDemoSettingsItStarts() {
    try (ConfigurableApplicationContext context = start("foliopay.keys=local", "foliopay.gateway=fake")) {
      assertThat(context.isRunning()).isTrue();
    }
  }

  /**
   * Fails if the hotel's OPERA settings have defaults. They used to default to the simulator's own (hotel DEMO1, payment method CC, cashier
   * 9001), so a deployment that left one out would post real payments under another cashier or card type, with no error (OHIP review, D-17).
   */
  @ParameterizedTest(name = "without foliopay.opera.{0}")
  @CsvSource({"hotel-id, hotelId", "payment-method, paymentMethod", "cashier-id, cashierId"})
  @DisplayName("Without each of the hotel's OPERA settings, the application does not start")
  void withoutEachOfTheHotelsOperaSettingsItDoesNotStart(String setting, String field) {
    List<String> allButOne = Stream.concat(HOTEL.stream().filter(p -> !p.startsWith("foliopay.opera." + setting + "=")),
        Stream.of("foliopay.keys=local", "foliopay.gateway=fake")).toList();
    assertThatThrownBy(() -> run(WebApplicationType.NONE, allButOne).close()).hasStackTraceContaining("foliopay.opera")
        .hasStackTraceContaining(field);
  }

  /** Fails if a real gateway can start with an open API (D-17, D-30): anyone could test stolen cards against the acquirer. */
  @Test
  @DisplayName("With a real gateway and no authentication, the application does not start")
  void withARealGatewayAndNoAuthenticationItDoesNotStart() {
    assertThatThrownBy(() -> startWeb("server.port=0", "foliopay.keys=local", "foliopay.gateway=http", "foliopay.allow-plaintext-transport=true",
        "foliopay.http.opera-base-url=http://127.0.0.1:1", "foliopay.http.opera-app-key=a", "foliopay.http.opera-client-id=b",
        "foliopay.http.opera-client-secret=c", "foliopay.http.opera-enterprise-id=d", "foliopay.http.opera-scope=e",
        "foliopay.http.psp-base-url=http://127.0.0.1:1", "foliopay.http.psp-api-key=d"))
        .hasStackTraceContaining("No authentication configured");
  }

  /** Fails if the TLS guard is not wired: a production start with the processor over plain HTTP would send card numbers in the clear (D-45). */
  @Test
  @DisplayName("With a real gateway over plain HTTP, the application does not start")
  void withARealGatewayOverPlainHttpItDoesNotStart() {
    assertThatThrownBy(() -> start("foliopay.keys=local", "foliopay.gateway=http",
        "foliopay.http.opera-base-url=https://opera.example.test", "foliopay.http.opera-app-key=a", "foliopay.http.opera-client-id=b",
        "foliopay.http.opera-client-secret=c", "foliopay.http.opera-enterprise-id=d", "foliopay.http.opera-scope=e",
        "foliopay.http.psp-base-url=http://psp.example.test", "foliopay.http.psp-api-key=d"))
        .hasStackTraceContaining("foliopay.http.psp-base-url (use https)");
  }

  /** Fails if the audit trail can be switched off: PCI DSS 10.2 needs every start, refusal and card access recorded (D-43). */
  @Test
  @DisplayName("With the audit trail switched off, the application does not start")
  void withTheAuditTrailSwitchedOffItDoesNotStart() {
    try {
      assertThatThrownBy(() -> start("foliopay.keys=local", "foliopay.gateway=fake", "logging.level.dev.foliopay.audit=OFF"))
          .hasStackTraceContaining("The audit trail");
    } finally {
      ((Logger) LoggerFactory.getLogger(AuditLog.LOGGER)).setLevel(Level.INFO); // the logging system is shared by every test in this JVM
    }
  }

  /** Fails if a token set-up that binds tokens to no issuer, or to no API (neither audience nor scope prefix), can start. */
  @Test
  @DisplayName("An OAuth2 set-up without issuer or audience does not start")
  void anOAuth2SetUpWithoutIssuerOrAudienceDoesNotStart() {
    assertThatThrownBy(() -> startWeb("server.port=0", "foliopay.keys=local", "foliopay.gateway=fake", "foliopay.auth.jwt.jwk-set-uri=https://127.0.0.1:1/jwks"))
        .hasStackTraceContaining("foliopay.auth.jwt.issuer is required");
    assertThatThrownBy(() -> startWeb("server.port=0", "foliopay.keys=local", "foliopay.gateway=fake", "foliopay.auth.jwt.jwk-set-uri=https://127.0.0.1:1/jwks",
        "foliopay.auth.jwt.issuer=https://auth.example.test")).hasStackTraceContaining("foliopay.auth.jwt.audience");
  }
}
