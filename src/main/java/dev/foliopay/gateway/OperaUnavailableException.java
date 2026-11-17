package dev.foliopay.gateway;

/** OPERA is down or timed out. Retry later with the same external reference. */
public class OperaUnavailableException extends RuntimeException {
  public OperaUnavailableException(String message) {
    super(message);
  }
}
