package dev.foliopay.domain.exception;

/** The request is not acceptable (400). */
public class InvalidRequestException extends DomainException {
  public InvalidRequestException(String message) {
    super(message);
  }
}
