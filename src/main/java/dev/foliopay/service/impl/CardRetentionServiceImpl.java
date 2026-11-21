package dev.foliopay.service.impl;

import dev.foliopay.config.VaultProperties;
import dev.foliopay.logging.AuditLog;
import dev.foliopay.logging.AuditLog.Event;
import dev.foliopay.repository.CardTokenRepository;
import dev.foliopay.service.CardRetentionService;
import dev.foliopay.service.support.Fingerprint;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Takes a batch at a time and drains the rest, up to a bounded number of batches per run; the next run carries on. Each card is purged by
 * one conditional update, so two instances running at once, or a card opened between the search and the purge, cannot go wrong.
 */
@Service
class CardRetentionServiceImpl implements CardRetentionService {
  private static final Logger log = LoggerFactory.getLogger(CardRetentionServiceImpl.class);
  private static final int MAX_BATCHES_PER_RUN = 100;
  private static final byte[] NOTHING = new byte[0];
  private final CardTokenRepository cards;
  private final VaultProperties settings;
  private final Clock clock;

  CardRetentionServiceImpl(CardTokenRepository cards, VaultProperties settings, Clock clock) {
    this.cards = cards;
    this.settings = settings;
    this.clock = clock;
  }

  @Override
  public int purgeExpired() {
    log.debug("purgeExpired(retention={}, batch={})", settings.retention(), settings.purgeBatch());
    Instant now = clock.instant();
    Instant cutoff = now.minus(settings.retention());
    int purged = 0;
    for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
      List<String> expired = cards.findExpired(cutoff, PageRequest.of(0, settings.purgeBatch()));
      for (String token : expired) {
        purged += purgeOne(token, cutoff, now) ? 1 : 0;
      }
      if (expired.size() < settings.purgeBatch()) {
        break;
      }
    }
    if (purged > 0) {
      log.info("Purged {} card numbers unused since before {}", purged, cutoff);
    }
    return purged;
  }

  private boolean purgeOne(String token, Instant cutoff, Instant now) {
    boolean purged = cards.purge(token, cutoff, now, NOTHING) == 1;
    if (purged) {
      AuditLog.event(Event.CARD_PURGED).resource(Fingerprint.card(token)).detail("unusedSince", cutoff).success();
    }
    return purged;
  }
}
