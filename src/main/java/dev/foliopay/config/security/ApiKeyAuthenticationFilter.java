package dev.foliopay.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code X-Api-Key} (the header Adyen and others use, so it never collides with an OAuth2 {@code Authorization: Bearer}).
 * A known key becomes an authentication with the key's scopes; an unknown key is refused at once, never treated as anonymous. Without the
 * header the request goes on to the bearer-token check.
 */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
  static final String HEADER = "X-Api-Key";
  private final ApiKeys keys;
  private final AuthenticationEntryPoint refuse;

  ApiKeyAuthenticationFilter(ApiKeys keys, AuthenticationEntryPoint refuse) {
    this.keys = keys;
    this.refuse = refuse;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String presented = request.getHeader(HEADER);
    if (presented == null) {
      chain.doFilter(request, response);
      return;
    }
    var client = keys.find(presented.trim());
    if (client.isEmpty()) {
      refuse.commence(request, response, new BadCredentialsException("Unknown API key"));
      return;
    }
    var authorities = client.get().scopes().stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(client.get().name(), null, authorities));
    SecurityContextHolder.setContext(context);
    chain.doFilter(request, response);
  }
}
