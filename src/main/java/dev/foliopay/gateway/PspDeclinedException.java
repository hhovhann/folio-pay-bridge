package dev.foliopay.gateway;

/** The processor refused the charge. Final: never retried. */
public class PspDeclinedException extends RuntimeException {
  public PspDeclinedException(String message) {
    super(message);
  }
}
