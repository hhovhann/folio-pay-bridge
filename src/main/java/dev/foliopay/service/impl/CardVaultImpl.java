package dev.foliopay.service.impl;

import dev.foliopay.crypto.Crypto;
import dev.foliopay.crypto.DataKey;
import dev.foliopay.crypto.KeyService;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.CardToken;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.logging.AuditLog;
import dev.foliopay.logging.AuditLog.Event;
import dev.foliopay.repository.CardTokenRepository;
import dev.foliopay.service.CardVault;
import dev.foliopay.service.support.Fingerprint;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Every card stored and every card opened is in the audit trail (PCI DSS v4.0.1 10.2.1.1), named by a fingerprint, never its token. */
@Service
class CardVaultImpl implements CardVault {
  private static final Logger log = LoggerFactory.getLogger(CardVaultImpl.class);
  private final KeyService keys;
  private final CardTokenRepository repository;
  private final Clock clock;

  CardVaultImpl(KeyService keys, CardTokenRepository repository, Clock clock) {
    this.keys = keys;
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public CardToken tokenise(Pan pan, String expiry) {
    log.debug("tokenise(card={})", pan);
    String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
    DataKey dataKey = keys.newDataKey(token);
    try {
      byte[] sealed = Crypto.seal(dataKey.plaintext(), pan.value().getBytes(StandardCharsets.UTF_8), token);
      CardToken saved = repository.save(new CardToken(token, sealed, dataKey.wrapped(), dataKey.keyId(), pan, expiry, clock.instant()));
      log.info("Tokenised card {} (key {})", pan, dataKey.keyId());
      AuditLog.event(Event.CARD_STORED).resource(Fingerprint.card(token)).detail("keyId", dataKey.keyId()).success();
      return saved;
    } finally {
      Arrays.fill(dataKey.plaintext(), (byte) 0);
    }
  }

  /** Opening a card renews its retention first: a purge that already won leaves nothing to open (D-44). */
  @Override
  public <T> T withCard(String token, BiFunction<Pan, String, T> use) {
    log.debug("withCard(card={})", Fingerprint.card(token));
    if (repository.markUsed(token, clock.instant()) == 0) {
      AuditLog.event(Event.CARD_OPENED).resource(Fingerprint.card(token)).detail("reason", "unknown-or-purged").failure();
      throw new NotFoundException("Unknown card token");
    }
    CardToken stored = repository.findById(token).orElseThrow(() -> new NotFoundException("Unknown card token"));
    byte[] key = keys.unwrap(stored.getWrappedKey(), token, stored.getKeyId());
    byte[] plain = null;
    try {
      plain = Crypto.open(key, stored.getSealedPan(), token);
      AuditLog.event(Event.CARD_OPENED).resource(Fingerprint.card(token)).detail("keyId", stored.getKeyId()).success();
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
    log.debug("exists(card={})", Fingerprint.card(token));
    return repository.existsByTokenAndPurgedAtIsNull(token);
  }
}
