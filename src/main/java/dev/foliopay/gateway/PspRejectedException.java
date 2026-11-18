package dev.foliopay.gateway;

/**
 * The processor refused the request itself, not the card: an unknown authorisation, a capture that does not match its authorisation, an
 * idempotency key reused with a different request, our credentials. A retry with the same key gets the same answer, so it is final like a
 * decline (no money was captured, and a hold is released), but it points at our side and is logged as an error for an operator.
 */
public class PspRejectedException extends PspDeclinedException {
  public PspRejectedException(String message) {
    super(message);
  }
}
