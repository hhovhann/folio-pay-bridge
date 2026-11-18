package dev.foliopay.config;

import dev.foliopay.api.ApiDeprecation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Adds the deprecation headers to every response of an endpoint marked {@link ApiDeprecation} (D-39) and logs the call with the client
 * (the client name is in the log context), so operators can see who still uses an old version before it is switched off.
 */
class ApiDeprecationInterceptor implements HandlerInterceptor {
  private static final Logger log = LoggerFactory.getLogger(ApiDeprecationInterceptor.class);

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    ApiDeprecation deprecation = method.getMethodAnnotation(ApiDeprecation.class);
    if (deprecation == null) {
      deprecation = method.getBeanType().getAnnotation(ApiDeprecation.class);
    }
    if (deprecation == null) {
      return true;
    }
    LocalDate sunset = LocalDate.parse(deprecation.sunset());
    response.setHeader("Deprecation", "@" + LocalDate.parse(deprecation.since()).atStartOfDay(ZoneOffset.UTC).toEpochSecond());
    response.setHeader("Sunset", DateTimeFormatter.RFC_1123_DATE_TIME.format(sunset.atStartOfDay(ZoneOffset.UTC)));
    if (!deprecation.successor().isBlank()) {
      response.setHeader("Link", "<" + deprecation.successor() + ">; rel=\"successor-version\"");
    }
    // The route pattern, not the raw path: it names the endpoint without carrying ids or anything else the caller put in the URL.
    log.info("Deprecated endpoint called: {} {} (sunset {})", request.getMethod(), request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), sunset);
    return true;
  }
}
