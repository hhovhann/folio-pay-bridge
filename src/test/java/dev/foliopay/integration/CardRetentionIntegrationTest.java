package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.AuditCapture;
import dev.foliopay.Fixtures;
import dev.foliopay.TestBase;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.logging.AuditLog.Event;
import dev.foliopay.repository.CardTokenRepository;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.service.CardRetentionService;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.support.Fingerprint;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Card retention on the real database (D-44, PCI DSS v4.0.1 3.2.1): the conditions that decide a purge are in its SQL, so only the database
 * can prove them (agent log 58). Time is moved by ageing a card's last use, against the 90 days in application.properties.
 */
@TestPropertySource(properties = "foliopay.vault.purge-batch=2")
@DisplayName("Card retention: an unused card number is destroyed after its period, and nothing still needed is")
class CardRetentionIntegrationTest extends TestBase {
  @Autowired private CardRetentionService retention;
  @Autowired private ChargeService chargeService;
  @Autowired private CardTokenRepository cards;
  @Autowired private ChargeRepository charges;
  @Autowired private JdbcTemplate jdbc;

  private void lastUsed(String card, Duration ago) {
    jdbc.update("update card_token set last_used_at = ? where token = ?", Timestamp.from(Instant.now().minus(ago)), card);
  }

  private Map<String, Object> row(String card) {
    return jdbc.queryForMap("select sealed_pan, wrapped_key, purged_at from card_token where token = ?", card);
  }

  private boolean purged(String card) {
    return row(card).get("purged_at") != null;
  }

  @Test
  @DisplayName("A card unused for longer than the period loses its number and can no longer be charged")
  void aCardUnusedForLongerThanThePeriodLosesItsNumberAndCanNoLongerBeCharged() {
    lastUsed(token, Duration.ofDays(91));
    try (AuditCapture audit = new AuditCapture()) {
      retention.purgeExpired();

      Map<String, Object> row = row(token);
      assertThat((byte[]) row.get("sealed_pan")).as("the ciphertext is gone").isEmpty();
      assertThat((byte[]) row.get("wrapped_key")).as("so is the key that could open it").isEmpty();
      assertThat(row.get("purged_at")).isNotNull();
      assertThat(vault.exists(token)).isFalse();
      assertThatThrownBy(() -> vault.withCard(token, (pan, expiry) -> pan)).isInstanceOf(NotFoundException.class);
      assertThat(audit.of(Event.CARD_PURGED)).anyMatch(line -> line.contains(Fingerprint.card(token))).noneMatch(line -> line.contains(token));
    }
  }

  @Test
  @DisplayName("A card used within the period is kept")
  void aCardUsedWithinThePeriodIsKept() {
    lastUsed(token, Duration.ofDays(89));
    retention.purgeExpired();

    assertThat(purged(token)).isFalse();
    String last4 = vault.withCard(token, (pan, expiry) -> pan.last4());
    assertThat(last4).isEqualTo("1111");
  }

  @Test
  @DisplayName("A card with a payment still pending is never purged, however old")
  void aCardWithAPaymentStillPendingIsNeverPurged() {
    charges.saveAndFlush(new Charge("", "pending-1", "BK-1", "F-1", LocalDate.of(2026, 1, 1), token, new Money(5_000, "USD")));
    lastUsed(token, Duration.ofDays(400));
    retention.purgeExpired();

    assertThat(purged(token)).isFalse();
  }

  @Test
  @DisplayName("Opening a card for an authorisation renews its period")
  void openingACardForAnAuthorisationRenewsItsPeriod() {
    lastUsed(token, Duration.ofDays(91));
    vault.withCard(token, (pan, expiry) -> pan);
    retention.purgeExpired();

    assertThat(purged(token)).isFalse();
  }

  /** Fails if the purge trusts its search: a card authorised between the search and the purge would lose its number mid-payment. */
  @Test
  @DisplayName("The purge checks the card again as it writes, so one opened since the search is kept")
  void thePurgeChecksTheCardAgainAsItWrites() {
    lastUsed(token, Duration.ofDays(91));
    Instant now = Instant.now();
    Instant cutoff = now.minus(Duration.ofDays(90));
    assertThat(cards.findExpired(cutoff, PageRequest.of(0, 10))).contains(token);

    cards.markUsed(token, now); // an authorisation opens the card after the search found it

    assertThat(cards.purge(token, cutoff, now, new byte[0])).isZero();
    assertThat(purged(token)).isFalse();
  }

  /** Fails if the purge trusts its search for pending payments: a charge started after the search would lose its card mid-payment. */
  @Test
  @DisplayName("The purge checks for a payment pending as it writes, so one started since the search keeps its card")
  void thePurgeChecksForAPaymentPendingAsItWrites() {
    lastUsed(token, Duration.ofDays(91));
    Instant now = Instant.now();
    charges.saveAndFlush(new Charge("", "pending-2", "BK-2", "F-1", LocalDate.of(2026, 1, 1), token, new Money(5_000, "USD")));

    assertThat(cards.purge(token, now.minus(Duration.ofDays(90)), now, new byte[0])).isZero();
    assertThat(purged(token)).isFalse();
  }

  /**
   * Fails if the search returns cards it cannot purge: with a batch of two, two old cards with payments pending would fill every batch,
   * the run would go round them until its bound, and the card behind them would never be purged.
   */
  @Test
  @DisplayName("Cards with a payment pending do not hold up the purge of the cards behind them")
  void cardsWithAPaymentPendingDoNotHoldUpThePurgeOfOthers() {
    List<String> held = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      String card = vault.tokenise(new Pan(PAN), "12/29").getToken();
      charges.saveAndFlush(new Charge("", "held-" + i, "BK-3", "F-1", LocalDate.of(2026, 1, 1), card, new Money(5_000, "USD")));
      lastUsed(card, Duration.ofDays(300));
      held.add(card);
    }
    lastUsed(token, Duration.ofDays(120));

    retention.purgeExpired();

    assertThat(purged(token)).as("the free card behind the two held ones").isTrue();
    assertThat(held).noneMatch(this::purged);
  }

  /** Fails if the card is checked before the key: a payment repeated after its card was purged would get 400 instead of its result. */
  @Test
  @DisplayName("A payment repeated after its card was purged gets its first result, and nothing is charged again")
  void aPaymentRepeatedAfterItsCardWasPurgedGetsItsFirstResult() {
    Charge first = chargeService.charge(Fixtures.command("after-purge-1", "BK-9", token, new Money(5_000, "USD")));
    assertThat(first.getStatus()).isEqualTo(Charge.Status.CAPTURED);
    lastUsed(token, Duration.ofDays(91));
    retention.purgeExpired();
    assertThat(purged(token)).isTrue();

    Charge repeat = chargeService.charge(Fixtures.command("after-purge-1", "BK-9", token, new Money(5_000, "USD")));

    assertThat(repeat.getId()).isEqualTo(first.getId());
    assertThat(repeat.getStatus()).isEqualTo(Charge.Status.CAPTURED);
    assertThat(psp.captureCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("A purge takes one batch at a time and drains them all")
  void aPurgeTakesOneBatchAtATimeAndDrainsThemAll() {
    List<String> old = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      old.add(vault.tokenise(new Pan(PAN), "12/29").getToken());
    }
    old.forEach(card -> lastUsed(card, Duration.ofDays(120)));

    assertThat(retention.purgeExpired()).as("five cards with a batch of two: three batches").isGreaterThanOrEqualTo(5);
    assertThat(old).allMatch(this::purged);
  }
}
