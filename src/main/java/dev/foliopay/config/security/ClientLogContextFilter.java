package dev.foliopay.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the authenticated client (an OAuth2 client id, or an API key's name; see {@link SecurityConfig#clientName}) into every log line of
 * the request: who did what (OWASP A09).
 */
final class ClientLogContextFilter extends OncePerRequestFilter {
  static final String MDC_KEY = "client";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken || auth.getName() == null) {
      chain.doFilter(request, response);
      return;
    }
    MDC.put(MDC_KEY, auth.getName().replaceAll("[^A-Za-z0-9._@:-]", "_")); // a claim is outside text: it cannot forge a log line
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
