package dev.foliopay.config;

import dev.foliopay.logging.AuditLog;
import dev.foliopay.logging.AuditLog.Event;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Every call that changes state goes into the audit trail with its client, path, method and status (PCI DSS v4.0.1 10.2.1.2): a payment,
 * an operator's replay or reconciliation, a demo switch. Reads are not audited: they change nothing, and no reply carries a card number.
 */
class AuditInterceptor implements HandlerInterceptor {
  private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
    if (READS.contains(request.getMethod())) {
      return;
    }
    AuditLog.Entry entry = AuditLog.event(Event.API_CALL).resource(request.getRequestURI())
        .detail("method", request.getMethod()).detail("status", response.getStatus());
    if (failure == null && response.getStatus() < 400) {
      entry.success();
    } else {
      entry.failure();
    }
  }
}
