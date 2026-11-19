package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.config.security.DashboardAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Security headers: what the dashboard's browser may contact")
class SecurityHeadersFilterTest {
  private static String policy(DashboardAuth auth) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new SecurityHeadersFilter(auth).doFilter(new MockHttpServletRequest("GET", "/dashboard/"), response, (req, res) -> {});
    return response.getHeader("Content-Security-Policy");
  }

  /** Fails if the dashboard's sign-in widens the policy beyond the identity provider: scripts stay this origin's only. */
  @Test
  @DisplayName("The browser may talk to the identity provider and nothing else")
  void theBrowserMayTalkToTheIdentityProviderAndNothingElse() throws Exception {
    String csp = policy(new DashboardAuth("https://idp.example.test/jwks", "", "https://idp.example.test/t1", "dash", "openid", "", false));
    assertThat(csp).contains("connect-src 'self' https://idp.example.test;").contains("script-src 'self';").doesNotContain("unsafe-eval");
  }

  @Test
  @DisplayName("Without sign-in, the browser may talk to this origin only")
  void withoutSignInTheBrowserMayTalkToThisOriginOnly() throws Exception {
    assertThat(policy(new DashboardAuth("", "", "", "", "openid", "", true))).contains("connect-src 'self';");
  }
}
