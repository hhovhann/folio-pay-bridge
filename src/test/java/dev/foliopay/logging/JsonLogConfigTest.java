package dev.foliopay.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Loads the real logback-json.xml, so the production JSON format cannot drop the card mask or the request id without failing here. */
class JsonLogConfigTest {
  @Test
  void eachLineIsOneJsonObjectWithTheRequestIdAndNoCardNumber() throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    LoggerContext context = new LoggerContext();
    context.setMDCAdapter(new LogbackMDCAdapter());
    try {
      System.setOut(new PrintStream(captured, true));
      URL config = getClass().getClassLoader().getResource("logback-json.xml");
      JoranConfigurator configurator = new JoranConfigurator();
      configurator.setContext(context);
      configurator.doConfigure(config);
      context.getMDCAdapter().put("requestId", "req-42");
      context.getLogger("dev.foliopay.test").info("oops card {} expiry 12/29", "4111111111111111");
      context.getLogger("dev.foliopay.test").error("failed", new IllegalStateException("bad request for 4242424242424242"));
      context.getLogger("dev.foliopay.test").info("forged\n{\"level\":\"INFO\",\"message\":\"fake entry\"}");
    } finally {
      System.setOut(original);
      context.stop();
    }
    List<JsonNode> lines = new ArrayList<>();
    for (String line : captured.toString().split("\n")) {
      lines.add(new ObjectMapper().readTree(line)); // fails if any line is not JSON
    }
    assertThat(lines).as("a line break in a value cannot start a new entry").hasSize(3);
    assertThat(lines.get(0).path("message").asText()).contains("************1111").doesNotContain("4111111111111111");
    assertThat(lines.get(0).path("requestId").asText()).isEqualTo("req-42");
    assertThat(lines.get(0).path("level").asText()).isEqualTo("INFO");
    assertThat(lines.get(1).path("exception").asText()).contains("IllegalStateException").doesNotContain("4242424242424242");
    assertThat(lines.get(2).path("message").asText()).startsWith("forged\n");
  }
}
