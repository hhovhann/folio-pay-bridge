package dev.foliopay.api.error;

import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.GoneException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.exception.NotFoundException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into one safe body in one place (OWASP A05, A09): domain errors by type, Spring's own web errors (404, 405, 415,
 * unreadable JSON) by status, and anything unexpected as a generic 500. Services stay free of web concerns and nothing leaks internals.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<ErrorResponse> invalid(InvalidRequestException e) { return reply(HttpStatus.BAD_REQUEST, e.getMessage()); }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ErrorResponse> notFound(NotFoundException e) { return reply(HttpStatus.NOT_FOUND, e.getMessage()); }

  @ExceptionHandler(ConflictException.class)
  ResponseEntity<ErrorResponse> conflict(ConflictException e) { return reply(HttpStatus.CONFLICT, e.getMessage()); }

  @ExceptionHandler(GoneException.class)
  ResponseEntity<ErrorResponse> gone(GoneException e) { return reply(HttpStatus.GONE, e.getMessage()); }

  /** A downstream system is down or not answering: the client should retry later with the same idempotency key. */
  @ExceptionHandler({dev.foliopay.gateway.OperaUnavailableException.class, dev.foliopay.gateway.PspTransientException.class})
  ResponseEntity<ErrorResponse> downstreamUnavailable(RuntimeException e) {
    log.warn("Downstream unavailable: {}", e.getClass().getSimpleName());
    return reply(HttpStatus.SERVICE_UNAVAILABLE, "A downstream system is unavailable: try again with the same idempotency key");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception e) {
    log.error("Unhandled error: {}", e.getClass().getName()); // class only: the message could carry request data
    return reply(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
  }

  /** Field names only: the rejected value may be a card number, so it is never echoed. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + " " + f.getDefaultMessage()).collect(Collectors.joining("; "));
    return ResponseEntity.status(status).body(new ErrorResponse(fields.isEmpty() ? "Invalid request" : fields));
  }

  /** Every other Spring MVC error keeps its own status but gets a fixed message, never the framework's detail. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String reason = status instanceof HttpStatus known ? known.getReasonPhrase() : "Request failed";
    return ResponseEntity.status(status).headers(headers).body(new ErrorResponse(reason));
  }

  private static ResponseEntity<ErrorResponse> reply(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ErrorResponse(message));
  }
}
