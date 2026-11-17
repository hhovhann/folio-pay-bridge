package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.crypto.LocalKeyService;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.CardToken;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.repository.CardTokenRepository;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real crypto and the real local key service; only the table is a map. */
class CardVaultImplTest {
  private static final String PAN = "4111111111111111";
  private final CardTokenRepository repository = mock(CardTokenRepository.class);
  private final Map<String, CardToken> table = new HashMap<>();
  private CardVaultImpl vault;

  @BeforeEach
  void setUp() {
    when(repository.save(any(CardToken.class))).thenAnswer(call -> {
      CardToken token = call.getArgument(0);
      table.put(token.getToken(), token);
      return token;
    });
    when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(table.get(call.<String>getArgument(0))));
    when(repository.existsById(any())).thenAnswer(call -> table.containsKey(call.<String>getArgument(0)));
    vault = new CardVaultImpl(new LocalKeyService(), repository);
  }

  @Test
  void storesOnlyCiphertextAndTheDisplayDigits() {
    CardToken stored = vault.tokenise(new Pan(PAN), "12/29");

    assertThat(new String(stored.getSealedPan(), StandardCharsets.ISO_8859_1)).doesNotContain(PAN);
    assertThat(stored.getBin6()).isEqualTo("411111");
    assertThat(stored.getLast4()).isEqualTo("1111");
    assertThat(stored.getToken()).startsWith("tok_");
  }

  @Test
  void lendsTheCardNumberToTheCallerAndNothingMore() {
    String token = vault.tokenise(new Pan(PAN), "12/29").getToken();

    String seen = vault.withCard(token, (pan, expiry) -> pan.value() + "|" + expiry);

    assertThat(seen).isEqualTo(PAN + "|12/29");
  }

  @Test
  void aCiphertextMovedToAnotherTokenCannotBeOpened() {
    CardToken a = vault.tokenise(new Pan(PAN), "12/29");
    CardToken b = vault.tokenise(new Pan("4242424242424242"), "11/28");
    CardToken forged = new CardToken(b.getToken(), a.getSealedPan(), b.getWrappedKey(), b.getKeyId(), new Pan(PAN), "12/29");
    table.put(b.getToken(), forged);

    assertThatThrownBy(() -> vault.withCard(b.getToken(), (pan, expiry) -> pan.value())).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anUnknownTokenIsNotFound() {
    assertThatThrownBy(() -> vault.withCard("tok_nope", (pan, expiry) -> pan)).isInstanceOf(NotFoundException.class);
    assertThat(vault.exists("tok_nope")).isFalse();
  }
}
