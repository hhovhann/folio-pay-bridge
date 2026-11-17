package dev.foliopay.domain.exception;

/** The thing asked for does not exist (404). */
public class NotFoundException extends DomainException {
  public NotFoundException(String message) {
    super(message);
  }
}
