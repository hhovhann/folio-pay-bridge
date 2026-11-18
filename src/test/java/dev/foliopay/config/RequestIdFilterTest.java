package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
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
  void anIdThatCouldForgeALogLineIsReplacedNotLogged() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/charges");
    request.addHeader("X-Request-Id", "x\nERROR forged entry");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> inLogs = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> inLogs.set(MDC.get(RequestIdFilter.MDC_KEY)));

    assertThat(inLogs.get()).doesNotContain("forged").matches("[0-9a-f-]{36}");
  }
}
