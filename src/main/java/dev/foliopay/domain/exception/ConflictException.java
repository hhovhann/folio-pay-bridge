package dev.foliopay.domain.exception;

/** The request clashes with existing state (409). */
public class ConflictException extends DomainException {
  public ConflictException(String message) {
    super(message);
  }
}
