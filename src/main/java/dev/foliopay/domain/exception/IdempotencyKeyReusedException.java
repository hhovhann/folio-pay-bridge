package dev.foliopay.domain.exception;

/** An idempotency key was sent again with different parameters (409, final): a retry cannot fix it, the client sent the wrong request. */
public class IdempotencyKeyReusedException extends ConflictException {
  public IdempotencyKeyReusedException() {
    super("Idempotency key was already used with different parameters");
  }
}
