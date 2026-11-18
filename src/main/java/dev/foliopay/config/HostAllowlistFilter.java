package dev.foliopay.config;

import dev.foliopay.api.error.ErrorCode;
import dev.foliopay.api.error.Problems;
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

/**
 * Refuses any Host header that is not on the allowlist, so a DNS-rebinding page cannot reach the loopback API from a browser (OWASP A05).
 * Health probes are exempt: a container check calls 127.0.0.1 and a load balancer sends the task's IP as the Host, and neither is the
 * public name. The probes reveal only UP or DOWN.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class HostAllowlistFilter extends OncePerRequestFilter {
  private static final String HEALTH = "/actuator/health";
  private final Set<String> allowed;

  HostAllowlistFilter(@Value("${foliopay.allowed-hosts:localhost,127.0.0.1,[::1]}") String hosts) {
    this.allowed = Arrays.stream(hosts.split(",")).map(h -> h.trim().toLowerCase()).filter(h -> !h.isEmpty()).collect(Collectors.toSet());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String name = request.getServerName() == null ? "" : request.getServerName().toLowerCase(); // Tomcat derives it from the Host header
    if (!allowed.contains(name) && !isHealthProbe(request)) {
      Problems.write(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Host not allowed");
      return;
    }
    chain.doFilter(request, response);
  }

  /** The probe itself and its groups, matched on the normalised path: {@code /actuator/health/../../api} is not a probe. */
  static boolean isHealthProbe(HttpServletRequest request) {
    String path;
    try {
      path = java.net.URI.create(request.getRequestURI()).normalize().getPath();
    } catch (IllegalArgumentException unparsable) {
      return false;
    }
    return HEALTH.equals(path) || (path != null && path.startsWith(HEALTH + "/") && !path.contains(".."));
  }
}
