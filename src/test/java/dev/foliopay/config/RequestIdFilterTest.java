package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Request id: kept in the log context, never able to forge a log line")
class RequestIdFilterTest {
  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  @DisplayName("A plain request id from the caller is kept in the log context and echoed")
  void aPlainIdFromTheCallerIsKeptInTheLogContextAndEchoed() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/charges");
    request.addHeader("X-Request-Id", "pms-42.a_b");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> inLogs = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> inLogs.set(MDC.get(RequestIdFilter.MDC_KEY)));

    assertThat(inLogs.get()).isEqualTo("pms-42.a_b");
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("pms-42.a_b");
    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
  }

  /** Fails if a caller's id goes into the logs unchecked: a line break in it would forge a log entry. */
  @Test
  @DisplayName("A request id that could forge a log line is replaced, not logged")
  void anIdThatCouldForgeALogLineIsReplacedNotLogged() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/charges");
    request.addHeader("X-Request-Id", "x\nERROR forged entry");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> inLogs = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> inLogs.set(MDC.get(RequestIdFilter.MDC_KEY)));

    assertThat(inLogs.get()).doesNotContain("forged").matches("[0-9a-f-]{36}");
  }
}
