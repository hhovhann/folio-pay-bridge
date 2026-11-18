package dev.foliopay.gateway;

/** OPERA refused the post (for example a closed folio). Retrying will not help. */
public class OperaRejectedException extends RuntimeException {
  public OperaRejectedException(String message) {
    super(message);
  }
}
