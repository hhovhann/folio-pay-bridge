package dev.foliopay.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;

/**
 * The only error body a client ever sees (D-29): an RFC 9457 problem detail ({@code application/problem+json}) with a short, safe
 * {@code detail} and a stable machine-readable {@code code}. Never a stack trace, never a submitted value. The code tells a client what to
 * do: a retryable one also carries {@code Retry-After} (see {@link ErrorCode}).
 */
public final class Problems {
  /** Spring's own mix-in puts the extra members ({@code code}) at the top level, as RFC 9457 says, the same as Spring MVC does. */
  private static final ObjectMapper JSON = new ObjectMapper().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

  private Problems() {}

  public static ProblemDetail of(HttpStatusCode status, ErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code.name());
    return problem;
  }

  /** For a request refused by a servlet filter, before Spring MVC: the same body, written by hand. Details are constants. */
  public static void write(HttpServletResponse response, int status, ErrorCode code, String detail) throws IOException {
    HttpStatus known = HttpStatus.resolve(status);
    ProblemDetail problem = of(HttpStatusCode.valueOf(status), code, detail);
    problem.setTitle(known == null ? "Request failed" : known.getReasonPhrase());
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter().write(JSON.writeValueAsString(problem));
  }
}
