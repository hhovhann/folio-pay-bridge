package dev.foliopay.api.error;

import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.GoneException;
import dev.foliopay.domain.exception.IdempotencyKeyReusedException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.logging.Redacted;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * unreadable JSON) by status, and anything unexpected as a generic 500. Every body carries a stable {@link ErrorCode}; a retryable one also
 * gets {@code Retry-After}. Services stay free of web concerns and nothing leaks internals.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  static final String RETRY_AFTER_SECONDS = "2";

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<ErrorResponse> invalid(InvalidRequestException e) { return reply(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, e.getMessage()); }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ErrorResponse> notFound(NotFoundException e) { return reply(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, e.getMessage()); }

  /** Final: the key was used for a different request. Retrying the same thing will get the same answer. */
  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ResponseEntity<ErrorResponse> keyReused(IdempotencyKeyReusedException e) { return reply(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_REUSED, e.getMessage()); }

  /** Another payment holds this link: try again once it has settled. */
  @ExceptionHandler(ConflictException.class)
  ResponseEntity<ErrorResponse> conflict(ConflictException e) { return reply(HttpStatus.CONFLICT, ErrorCode.PAYMENT_IN_PROGRESS, e.getMessage()); }

  @ExceptionHandler(GoneException.class)
  ResponseEntity<ErrorResponse> gone(GoneException e) { return reply(HttpStatus.GONE, ErrorCode.GONE, e.getMessage()); }

  /** A downstream system is down or not answering: the client should retry later with the same idempotency key. */
  @ExceptionHandler({OperaUnavailableException.class, PspTransientException.class})
  ResponseEntity<ErrorResponse> downstreamUnavailable(RuntimeException e) {
    log.warn("Downstream unavailable: {}", e.getClass().getSimpleName());
    return reply(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DOWNSTREAM_UNAVAILABLE, "A downstream system is unavailable: try again with the same idempotency key");
  }

  /** Another request changed the same record first (two booking events for one booking, an event racing a payment): nothing was lost, send it again. */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ErrorResponse> concurrentChange(RuntimeException e) {
    log.info("Concurrent change: {}", e.getClass().getSimpleName());
    return reply(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_UPDATE, "The record was changed by another request at the same time: send it again");
  }

  /**
   * The stack trace of every exception in the chain is logged, with the request id from the log context, but no exception message: a
   * message can carry request data the card mask cannot recognise (CLAUDE.md rule 1). The client gets nothing but a code.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest request) {
    log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), Redacted.of(e));
    return reply(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Something went wrong");
  }

  /** Field names only: the rejected value may be a card number, so it is never echoed. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + " " + f.getDefaultMessage()).collect(Collectors.joining("; "));
    return ResponseEntity.status(status).body(new ErrorResponse(fields.isEmpty() ? "Invalid request" : fields, ErrorCode.INVALID_REQUEST.name()));
  }

  /** Every other Spring MVC error keeps its own status but gets a fixed message, never the framework's detail. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String reason = status instanceof HttpStatus known ? known.getReasonPhrase() : "Request failed";
    ErrorCode code = status.value() == HttpStatus.NOT_FOUND.value() ? ErrorCode.NOT_FOUND : status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.REQUEST_FAILED;
    return ResponseEntity.status(status).headers(headers).body(new ErrorResponse(reason, code.name()));
  }

  private static ResponseEntity<ErrorResponse> reply(HttpStatus status, ErrorCode code, String message) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
    if (code.retryable()) {
      builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    }
    return builder.body(new ErrorResponse(message, code.name()));
  }
}
