package dev.foliopay.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Refuses any Host header that is not on the allowlist, so a DNS-rebinding page cannot reach the loopback API from a browser (OWASP A05). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class HostAllowlistFilter extends OncePerRequestFilter {
  private final Set<String> allowed;

  HostAllowlistFilter(@Value("${foliopay.allowed-hosts:localhost,127.0.0.1,[::1]}") String hosts) {
    this.allowed = Arrays.stream(hosts.split(",")).map(h -> h.trim().toLowerCase()).filter(h -> !h.isEmpty()).collect(Collectors.toSet());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String name = request.getServerName() == null ? "" : request.getServerName().toLowerCase(); // Tomcat derives it from the Host header
    if (!allowed.contains(name)) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"Host not allowed\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
