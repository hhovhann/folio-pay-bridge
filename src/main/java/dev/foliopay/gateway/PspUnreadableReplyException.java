package dev.foliopay.gateway;

/**
 * The processor answered "success" but the reply cannot be used (no reference: an API change, a proxy). The step may have happened, so
 * the charge must stay PENDING with the hold in place, never be recorded as captured with a blank reference or declined (D-34). The
 * message is a constant: it never carries request data.
 */
public class PspUnreadableReplyException extends RuntimeException {
  public PspUnreadableReplyException(String message) {
    super(message);
  }
}
