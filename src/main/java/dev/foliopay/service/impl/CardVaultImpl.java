package dev.foliopay.service.impl;

import dev.foliopay.crypto.Crypto;
import dev.foliopay.crypto.DataKey;
import dev.foliopay.crypto.KeyService;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.CardToken;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.repository.CardTokenRepository;
import dev.foliopay.service.CardVault;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class CardVaultImpl implements CardVault {
  private static final Logger log = LoggerFactory.getLogger(CardVaultImpl.class);
  private final KeyService keys;
  private final CardTokenRepository repository;

  CardVaultImpl(KeyService keys, CardTokenRepository repository) {
    this.keys = keys;
    this.repository = repository;
  }

  @Override
  public CardToken tokenise(Pan pan, String expiry) {
    String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
    DataKey dataKey = keys.newDataKey(token);
    try {
      byte[] sealed = Crypto.seal(dataKey.plaintext(), pan.value().getBytes(StandardCharsets.UTF_8), token);
      CardToken saved = repository.save(new CardToken(token, sealed, dataKey.wrapped(), keys.keyId(), pan, expiry));
      log.info("Tokenised card {} (key {})", pan, keys.keyId());
      return saved;
    } finally {
      Arrays.fill(dataKey.plaintext(), (byte) 0);
    }
  }

  @Override
  public <T> T withCard(String token, BiFunction<Pan, String, T> use) {
    CardToken stored = repository.findById(token).orElseThrow(() -> new NotFoundException("Unknown card token"));
    byte[] key = keys.unwrap(stored.getWrappedKey(), token);
    byte[] plain = null;
    try {
      plain = Crypto.open(key, stored.getSealedPan(), token);
      return use.apply(new Pan(new String(plain, StandardCharsets.UTF_8)), stored.getExpiry());
    } finally {
      Arrays.fill(key, (byte) 0);
      if (plain != null) {
        Arrays.fill(plain, (byte) 0);
      }
    }
  }

  @Override
  public boolean exists(String token) {
    return repository.existsById(token);
  }
}
