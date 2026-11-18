package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.FolioPayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The application must refuse to start rather than quietly run with a throwaway key or with fakes that pretend to move money. */
class FailClosedStartupTest {
  private ConfigurableApplicationContext start(String... properties) {
    String[] args = java.util.Arrays.stream(properties).map(p -> "--" + p).toArray(String[]::new); // command-line arguments outrank application.properties
    return new SpringApplicationBuilder(FolioPayApplication.class).web(WebApplicationType.NONE).run(args);
  }

  @Test
  void withNoKeyModeItDoesNotStart() {
    assertThatThrownBy(() -> start("foliopay.keys=", "foliopay.gateway=fake")).hasStackTraceContaining("KeyService");
  }

  @Test
  void withNoGatewayItDoesNotStartInsteadOfPretendingToChargeCards() {
    assertThatThrownBy(() -> start("foliopay.keys=local", "foliopay.gateway=")).hasStackTraceContaining("PspClient");
  }

  @Test
  void withTheDemoSettingsItStarts() {
    try (ConfigurableApplicationContext context = start("foliopay.keys=local", "foliopay.gateway=fake")) {
      assertThat(context.isRunning()).isTrue();
    }
  }

  private ConfigurableApplicationContext startWeb(String... properties) {
    String[] args = java.util.Arrays.stream(properties).map(p -> "--" + p).toArray(String[]::new);
    return new SpringApplicationBuilder(FolioPayApplication.class).web(WebApplicationType.SERVLET).run(args);
  }

  /** Fails if a real gateway can start with an open API (D-17, D-30): anyone could test stolen cards against the acquirer. */
  @Test
  void withARealGatewayAndNoAuthenticationItDoesNotStart() {
    assertThatThrownBy(() -> startWeb("server.port=0", "foliopay.keys=local", "foliopay.gateway=http", "foliopay.http.opera-base-url=http://127.0.0.1:1",
        "foliopay.http.opera-app-key=a", "foliopay.http.opera-client-id=b", "foliopay.http.opera-client-secret=c", "foliopay.http.psp-base-url=http://127.0.0.1:1",
        "foliopay.http.psp-api-key=d")).hasStackTraceContaining("No authentication configured");
  }

  @Test
  void anOAuth2SetUpWithoutIssuerOrAudienceDoesNotStart() {
    assertThatThrownBy(() -> startWeb("server.port=0", "foliopay.keys=local", "foliopay.gateway=fake", "foliopay.auth.jwt.jwk-set-uri=http://127.0.0.1:1/jwks"))
        .hasStackTraceContaining("foliopay.auth.jwt.issuer and foliopay.auth.jwt.audience are required");
  }
}

