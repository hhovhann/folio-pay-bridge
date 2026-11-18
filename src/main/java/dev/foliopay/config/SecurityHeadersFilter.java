package dev.foliopay.config;

import dev.foliopay.config.security.DashboardAuth;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Browser-side hardening (OWASP A05): no sniffing, no framing, a tight content security policy, and API responses never cached. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // before any filter that can refuse a request, so refusals carry the headers too
class SecurityHeadersFilter extends OncePerRequestFilter {
  private final String csp;

  /** The browser may fetch from this origin only, plus the identity provider the dashboard signs in with (when one is configured). */
  SecurityHeadersFilter(DashboardAuth dashboardAuth) {
    String connect = dashboardAuth.connectOrigins().isBlank() ? "'self'" : "'self' " + dashboardAuth.connectOrigins();
    this.csp = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src " + connect
        + "; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'";
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Content-Security-Policy", csp);
    if (request.getRequestURI().startsWith("/api/")) {
      response.setHeader("Cache-Control", "no-store");
    }
    chain.doFilter(request, response);
  }
}
