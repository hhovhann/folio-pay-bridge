package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.config.security.DashboardAuth;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {
  private static String policy(DashboardAuth auth) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new SecurityHeadersFilter(auth).doFilter(new MockHttpServletRequest("GET", "/dashboard/"), response, (req, res) -> {});
    return response.getHeader("Content-Security-Policy");
  }

  /** Fails if the dashboard's sign-in widens the policy beyond the identity provider: scripts stay this origin's only. */
  @Test
  void theBrowserMayTalkToTheIdentityProviderAndNothingElse() throws Exception {
    String csp = policy(new DashboardAuth("https://idp.example.test/jwks", "", "https://idp.example.test/t1", "dash", "openid", "", false));
    assertThat(csp).contains("connect-src 'self' https://idp.example.test;").contains("script-src 'self';").doesNotContain("unsafe-eval");
  }

  @Test
  void withoutSignInTheBrowserMayTalkToThisOriginOnly() throws Exception {
    assertThat(policy(new DashboardAuth("", "", "", "", "openid", "", true))).contains("connect-src 'self';");
  }
}
