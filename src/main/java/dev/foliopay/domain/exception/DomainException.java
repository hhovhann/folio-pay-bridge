package dev.foliopay.domain.exception;

/** Base of every business error. Carries a message that is safe to show a client: never a card number, never internals. */
public abstract class DomainException extends RuntimeException {
  protected DomainException(String message) {
    super(message);
  }
}
