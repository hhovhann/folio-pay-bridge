package dev.foliopay.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects oversized request bodies before they are parsed (OWASP A04): the largest legitimate body is a pasted settlement statement. */
@Component
class RequestSizeFilter extends OncePerRequestFilter {
  private static final long MAX_BYTES = 1_000_000;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BYTES) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"Request body too large\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
