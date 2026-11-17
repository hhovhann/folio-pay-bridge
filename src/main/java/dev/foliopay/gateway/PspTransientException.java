package dev.foliopay.gateway;

/** The call may or may not have reached the processor. Safe to retry with the same idempotency key. */
public class PspTransientException extends RuntimeException {
  public PspTransientException(String message) {
    super(message);
  }
}
