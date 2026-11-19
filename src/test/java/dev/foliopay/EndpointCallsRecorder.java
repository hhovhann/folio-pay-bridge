package dev.foliopay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Records every request a test sends through MockMvc (method, path, status), in every controller and whole-application test, and writes
 * them to {@code target/endpoint-calls.tsv} when the test run ends. {@code tools/check_specs.py} then requires, for every operation in
 * {@code docs/openapi.json}, at least one success and at least one refusal among the requests the tests really sent: positive and negative
 * cases measured, not listed by hand (standards section 10). Imported wherever {@code @AutoConfigureMockMvc} is, through
 * {@code META-INF/spring/...AutoConfigureMockMvc.imports} in the test resources.
 */
@AutoConfiguration
public class EndpointCallsRecorder {
  private static final Set<String> CALLS = ConcurrentHashMap.newKeySet();

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(EndpointCallsRecorder::write));
  }

  @Bean
  MockMvcBuilderCustomizer recordEndpointCalls() {
    return builder -> builder.alwaysDo(result ->
        CALLS.add(result.getRequest().getMethod() + "\t" + result.getRequest().getRequestURI() + "\t" + result.getResponse().getStatus()));
  }

  private static void write() {
    try {
      Path out = Path.of("target", "endpoint-calls.tsv");
      Files.createDirectories(out.getParent());
      Files.write(out, new TreeSet<>(CALLS));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
