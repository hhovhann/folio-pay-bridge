package dev.foliopay.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Review finding: logging whole exceptions relied on the Luhn mask, which cannot see a mistyped or split card number in a message. */
@DisplayName("Redacted exceptions: stack traces kept, messages never logged")
class RedactedTest {
  private static String printed(Throwable t) {
    StringWriter out = new StringWriter();
    t.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  @Test
  @DisplayName("The stack trace and the cause chain are kept, but no exception message ever is")
  void theStackTraceAndTheCauseChainAreKeptButNoMessageIsEver() {
    IllegalStateException root = new IllegalStateException("Unrecognized token '4111 1111 1111 1112': was expecting a number");
    RuntimeException top = new RuntimeException("while handling card 4111-1111-1111-111", root);

    String printed = printed(Redacted.of(top));

    assertThat(printed).doesNotContain("4111").doesNotContain("Unrecognized token").doesNotContain("while handling");
    assertThat(printed).contains("java.lang.RuntimeException", "java.lang.IllegalStateException",
        "RedactedTest.theStackTraceAndTheCauseChainAreKeptButNoMessageIsEver");
  }

  @Test
  @DisplayName("A self-referencing or very deep cause chain ends")
  void aSelfReferencingOrVeryDeepCauseChainEnds() {
    RuntimeException deep = new RuntimeException("0");
    for (int i = 1; i < 50; i++) {
      deep = new RuntimeException(String.valueOf(i), deep);
    }
    int depth = 0;
    for (Throwable t = Redacted.of(deep); t != null; t = t.getCause()) {
      depth++;
    }
    assertThat(depth).isEqualTo(10);
    assertThat(Redacted.of(null)).isNull();
  }
}
