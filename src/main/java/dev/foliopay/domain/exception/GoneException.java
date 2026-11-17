package dev.foliopay.domain.exception;

/** The thing existed but can no longer be used (410). */
public class GoneException extends DomainException {
  public GoneException(String message) {
    super(message);
  }
}
