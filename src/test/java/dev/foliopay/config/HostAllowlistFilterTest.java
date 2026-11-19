package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Review finding: with only the public host allowed, the container and load-balancer health checks got 400 and ECS replaced every task. */
@DisplayName("Host allowlist: only allowed hosts reach the API, and only the health probe is exempt")
class HostAllowlistFilterTest {
  private final HostAllowlistFilter filter = new HostAllowlistFilter("pay.example.com");

  private static MockHttpServletRequest to(String host, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServerName(host);
    return request;
  }

  @Test
  @DisplayName("A health probe by IP or loopback is answered whatever the Host header")
  void aHealthProbeByIpOrLoopbackIsAnsweredWhateverTheHost() throws Exception {
    for (String host : new String[] {"127.0.0.1", "10.0.3.17"}) {
      AtomicBoolean reached = new AtomicBoolean();
      filter.doFilter(to(host, "/actuator/health/readiness"), new MockHttpServletResponse(), (req, res) -> reached.set(true));
      assertThat(reached).as(host).isTrue();
    }
  }

  @Test
  @DisplayName("The API under an unknown host is still refused")
  void theApiUnderAnUnknownHostIsStillRefused() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(to("10.0.3.17", "/api/v1/charges"), response, (req, res) -> { throw new AssertionError("reached"); });
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("Host not allowed");
  }

  /** A path the URI parser refuses (a raw space) gets no exemption from the host check: unparsable is never "a probe". */
  @Test
  @DisplayName("A path that cannot be parsed is not a health probe")
  void aPathThatCannotBeParsedIsNotAProbe() {
    assertThat(HostAllowlistFilter.isHealthProbe(to("10.0.3.17", "/actuator/health/a b"))).isFalse();
  }

  /** Fails if the exemption matches a prefix of the raw path: a rebinding page could reach the API through it. */
  @Test
  @DisplayName("Only the probe itself is exempt, not a path that merely starts like it")
  void onlyTheProbeItselfIsExemptNotAPathThatMerelyStartsLikeIt() throws Exception {
    for (String path : new String[] {"/actuator/health/../../api/charges", "/actuator/healthX", "/actuator/health/..;/api"}) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(to("10.0.3.17", path), response, (req, res) -> { throw new AssertionError("reached " + path); });
      assertThat(response.getStatus()).as(path).isEqualTo(400);
    }
  }
}
