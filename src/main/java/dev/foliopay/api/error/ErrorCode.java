package dev.foliopay.api.error;

/**
 * Stable error codes, so a client never has to parse a message. Two 409s mean opposite things: {@code IDEMPOTENCY_KEY_REUSED} is final (the
 * client sent a different request under an old key), {@code CONCURRENT_UPDATE} is "send the same request again".
 */
public enum ErrorCode {
  INVALID_REQUEST(false),
  UNAUTHORIZED(false),
  FORBIDDEN(false),
  NOT_FOUND(false),
  IDEMPOTENCY_KEY_REUSED(false),
  PAYMENT_IN_PROGRESS(true),
  CONCURRENT_UPDATE(true),
  GONE(false),
  BODY_TOO_LARGE(false),
  DOWNSTREAM_UNAVAILABLE(true),
  INTERNAL_ERROR(false),
  REQUEST_FAILED(false);

  private final boolean retryable;

  ErrorCode(boolean retryable) {
    this.retryable = retryable;
  }

  /** True when the same request, with the same idempotency key, may succeed if sent again later. */
  public boolean retryable() { return retryable; }
}
