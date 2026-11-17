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
}
