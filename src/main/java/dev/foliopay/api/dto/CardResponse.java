package dev.foliopay.api.dto;

import dev.foliopay.domain.model.CardToken;

public record CardResponse(String token, String bin6, String last4, String expiry) {
  public static CardResponse from(CardToken t) {
    return new CardResponse(t.getToken(), t.getBin6(), t.getLast4(), t.getExpiry());
  }

  /** The token goes to the caller in the body, never into a log line through this object (D-41). */
  @Override
  public String toString() {
    return "CardResponse[token=[redacted], bin6=" + bin6 + ", last4=" + last4 + ", expiry=" + expiry + "]";
  }
}
