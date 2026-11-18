package dev.foliopay.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import org.junit.jupiter.api.Test;

/** Loads the real logback-spring.xml, so removing the masking converter from the production config fails this test. */
class ProductionLogConfigTest {
  @Test
  void aCardNumberLoggedByMistakeIsMaskedOnTheConsole() throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    LoggerContext context = new LoggerContext();
    context.setMDCAdapter(new LogbackMDCAdapter()); // a standalone context has none
    try {
      System.setOut(new PrintStream(captured, true));
      URL config = getClass().getClassLoader().getResource("logback-spring.xml");
      JoranConfigurator configurator = new JoranConfigurator();
      configurator.setContext(context);
      configurator.doConfigure(config);
      context.getLogger("dev.foliopay.test").info("oops card {} expiry 12/29", "4111111111111111");
      context.getLogger("dev.foliopay.test").error("failed", new IllegalStateException("bad request for 4242424242424242"));
      context.getLogger("dev.foliopay.test").info("forged\nlineWARN fake entry");
    } finally {
      System.setOut(original);
      context.stop();
    }
    String output = captured.toString();
    assertThat(output).contains("************1111").doesNotContain("4111111111111111");
    assertThat(output).as("exception messages are masked too").doesNotContain("4242424242424242").contains("IllegalStateException");
    assertThat(output).as("a line break in a value cannot start a new log entry").doesNotContain("\nlineWARN").contains("forged\\nlineWARN");
  }
}
