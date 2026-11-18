package dev.foliopay.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts the authenticated client (an OAuth2 client id, or an API key's name) into every log line of the request: who did what (OWASP A09). */
final class ClientLogContextFilter extends OncePerRequestFilter {
  static final String MDC_KEY = "client";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String client = auth == null || !auth.isAuthenticated() ? null
        : auth.getPrincipal() instanceof Jwt jwt ? (jwt.hasClaim("client_id") ? jwt.getClaimAsString("client_id") : jwt.getSubject()) : auth.getName();
    if (client == null || "anonymousUser".equals(client)) {
      chain.doFilter(request, response);
      return;
    }
    MDC.put(MDC_KEY, client.replaceAll("[^A-Za-z0-9._@:-]", "_")); // a claim is outside text: it cannot forge a log line
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
