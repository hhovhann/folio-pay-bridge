package dev.foliopay.api.dto;

import dev.foliopay.domain.model.CardToken;

public record CardResponse(String token, String bin6, String last4, String expiry) {
  public static CardResponse from(CardToken t) {
    return new CardResponse(t.getToken(), t.getBin6(), t.getLast4(), t.getExpiry());
  }
}
