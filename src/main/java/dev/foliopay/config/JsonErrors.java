package dev.foliopay.config;

import dev.foliopay.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** The error body for requests refused by a filter, before Spring MVC: the same shape as {@code ErrorResponse}. Messages are constants. */
public final class JsonErrors {
  private JsonErrors() {}

  public static void write(HttpServletResponse response, int status, ErrorCode code, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"" + code.name() + "\"}");
  }
}
