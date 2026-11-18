package dev.foliopay.service;

import dev.foliopay.domain.model.CardToken;
import dev.foliopay.domain.model.Pan;
import java.util.function.BiFunction;

/** Tokenises cards and lends the plaintext to a caller for the length of one call. The number never leaves the vault in a return value. */
public interface CardVault {
  CardToken tokenise(Pan pan, String expiry);

  /** Calls {@code use} with the card number and expiry, then wipes the key material. The only way to read a card number. */
  <T> T withCard(String token, BiFunction<Pan, String, T> use);

  boolean exists(String token);
}
