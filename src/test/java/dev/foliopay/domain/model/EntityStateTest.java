package dev.foliopay.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.Fixtures;
import dev.foliopay.domain.model.Posting.FailureCategory;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The state rules of the money records: what a request must match, which state wins a race, and what each transition clears. */
@DisplayName("Money records: the state rules of charges, postings, links and stored cards")
class EntityStateTest {
  private static final Money TEN = new Money(1_000, "USD");
  private static final LocalDate DAY = Fixtures.DAY;

  @Test
  @DisplayName("A charge matches a repeat only when every field that decides where the money goes is the same")
  void aChargeMatchesARepeatOnlyWhenEveryFieldThatDecidesWhereTheMoneyGoesIsTheSame() {
    Charge charge = new Charge(Charge.BRIDGE_CLIENT, "k", "BK-1", "F-1", DAY, "tok_1", TEN);
    assertThat(charge.matches("BK-1", "F-1", DAY, "tok_1", TEN)).isTrue();
    assertThat(charge.matches("BK-2", "F-1", DAY, "tok_1", TEN)).isFalse();
    assertThat(charge.matches("BK-1", "F-2", DAY, "tok_1", TEN)).isFalse();
    assertThat(charge.matches("BK-1", "F-1", DAY.plusDays(1), "tok_1", TEN)).isFalse();
    assertThat(charge.matches("BK-1", "F-1", null, "tok_1", TEN)).as("a repeat that names no date means the date it was taken on").isTrue();
    assertThat(charge.matches("BK-2", "F-1", null, "tok_1", TEN)).as("no date does not loosen anything else").isFalse();
    assertThat(charge.matches("BK-1", "F-1", DAY, "tok_2", TEN)).isFalse();
    assertThat(charge.matches("BK-1", "F-1", DAY, "tok_1", new Money(1_001, "USD"))).isFalse();
    assertThat(charge.matches("BK-1", "F-1", DAY, "tok_1", new Money(1_000, "EUR"))).isFalse();
  }

  @Test
  @DisplayName("A capture beats a decline, which beats pending")
  void aCaptureBeatsADeclineWhichBeatsPending() {
    Charge pending = Fixtures.charge("k", "BK", "tok", TEN);
    Charge declined = Fixtures.charge("k", "BK", "tok", TEN);
    declined.markDeclined("no");
    Charge captured = Fixtures.charge("k", "BK", "tok", TEN);
    captured.markCaptured("cap_1");

    assertThat(pending.isBehind(declined)).isTrue();
    assertThat(pending.isBehind(captured)).isTrue();
    assertThat(declined.isBehind(captured)).isTrue();
    assertThat(captured.isBehind(declined)).isFalse();
    assertThat(captured.isBehind(pending)).isFalse();
    assertThat(pending.isBehind(pending)).isFalse();
  }

  @Test
  @DisplayName("Adopting a capture carries the result and never lowers the attempt count")
  void adoptingACaptureCarriesTheResultAndNeverLowersTheAttemptCount() {
    Charge stored = Fixtures.charge("k", "BK", "tok", TEN);
    stored.recordFailedAttempt("timeout");
    stored.recordFailedAttempt("timeout");
    Charge ours = Fixtures.charge("k", "BK", "tok", TEN);
    ours.markCaptured("cap_9");

    stored.adopt(ours);

    assertThat(stored.getStatus()).isEqualTo(Charge.Status.CAPTURED);
    assertThat(stored.getPspRef()).isEqualTo("cap_9");
    assertThat(stored.getLastError()).isNull();
    assertThat(stored.getAttempts()).isEqualTo(2);
  }

  @Test
  @DisplayName("A charge exposes what it was created with")
  void aChargeExposesWhatItWasCreatedWith() {
    Charge charge = new Charge(Charge.BRIDGE_CLIENT, "k", "BK-1", "F-1", DAY, "tok_1", TEN);
    assertThat(charge.getId()).isNotBlank();
    assertThat(charge.getIdempotencyKey()).isEqualTo("k");
    assertThat(charge.getBookingRef()).isEqualTo("BK-1");
    assertThat(charge.getFolioId()).isEqualTo("F-1");
    assertThat(charge.getBusinessDate()).isEqualTo(DAY);
    assertThat(charge.getCardToken()).isEqualTo("tok_1");
    assertThat(charge.amount()).isEqualTo(TEN);
    assertThat(charge.getAmountMinor()).isEqualTo(1_000);
    assertThat(charge.getCurrency()).isEqualTo("USD");
    assertThat(charge.getStatus()).isEqualTo(Charge.Status.PENDING);
    assertThat(charge.getAttempts()).isZero();
    assertThat(charge.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("Posted beats a dead letter, which beats pending")
  void aPostedBeatsADeadLetterWhichBeatsPending() {
    Posting pending = Fixtures.posting("p", "c", "F", TEN, DAY);
    Posting dead = Fixtures.posting("p", "c", "F", TEN, DAY);
    dead.moveToDeadLetter(FailureCategory.OPERA_UNAVAILABLE, "503");
    Posting posted = Fixtures.posting("p", "c", "F", TEN, DAY);
    posted.markPosted("OP-1");

    assertThat(pending.isBehind(dead)).isTrue();
    assertThat(pending.isBehind(posted)).isTrue();
    assertThat(dead.isBehind(posted)).isTrue();
    assertThat(posted.isBehind(dead)).isFalse();
    assertThat(posted.isBehind(pending)).isFalse();
  }

  @Test
  @DisplayName("Adopting a post carries it and clears the failure")
  void adoptingAPostCarriesItAndClearsTheFailure() {
    Posting stored = Fixtures.posting("p", "c", "F", TEN, DAY);
    stored.recordAttempt();
    stored.moveToDeadLetter(FailureCategory.OPERA_UNAVAILABLE, "503");
    Posting ours = Fixtures.posting("p", "c", "F", TEN, DAY);
    ours.markPosted("OP-7");

    stored.adopt(ours);

    assertThat(stored.isPosted()).isTrue();
    assertThat(stored.getOperaRef()).isEqualTo("OP-7");
    assertThat(stored.getFailureCategory()).isNull();
    assertThat(stored.getLastError()).isNull();
    assertThat(stored.getAttempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("A posting exposes what it was created with and knows when it is rejected")
  void aPostingExposesWhatItWasCreatedWithAndKnowsWhenItIsRejected() {
    Posting posting = new Posting("post:c", "c", "RES-1", TEN, DAY, "cap_1");
    assertThat(posting.getChargeId()).isEqualTo("c");
    assertThat(posting.getFolioId()).isEqualTo("RES-1");
    assertThat(posting.getBusinessDate()).isEqualTo(DAY);
    assertThat(posting.getPspRef()).isEqualTo("cap_1");
    assertThat(posting.amount()).isEqualTo(TEN);
    assertThat(posting.getAmountMinor()).isEqualTo(1_000);
    assertThat(posting.getCurrency()).isEqualTo("USD");
    assertThat(posting.getCreatedAt()).isNotNull();
    assertThat(posting.isRejected()).isFalse();
    posting.moveToDeadLetter(FailureCategory.REJECTED_BY_OPERA, "closed");
    assertThat(posting.isRejected()).isTrue();
    assertThat(posting.getFailureCategory()).isEqualTo(FailureCategory.REJECTED_BY_OPERA);
  }

  @Test
  @DisplayName("A payment link moves through its states and frees its booking slot when closed")
  void aPaymentLinkMovesThroughItsStatesAndFreesItsBookingSlotWhenClosed() {
    PaymentLink link = new PaymentLink("B1", "RES-B1", TEN, DAY, "reason");
    assertThat(link.isOpen()).isTrue();
    assertThat(link.isPaying()).isFalse();
    assertThat(link.isPaid()).isFalse();
    assertThat(link.hasTerms(link.getFolioId(), new Money(1_000, link.getCurrency()), DAY)).isTrue();
    assertThat(link.hasTerms(link.getFolioId(), new Money(999, link.getCurrency()), DAY)).isFalse();
    assertThat(link.hasTerms(link.getFolioId(), new Money(1_000, link.getCurrency()), DAY.plusDays(1))).isFalse();
    assertThat(link.hasTerms(link.getFolioId(), new Money(1_000, "EUR"), DAY)).as("same number, another currency").isFalse();
    assertThat(link.hasTerms("F-OTHER", new Money(1_000, link.getCurrency()), DAY)).as("another folio").isFalse();
    assertThat(link.isHeldBy("anyone")).isFalse();
    assertThat(link.getId()).startsWith("lnk_").hasSize(36);
    assertThat(link.getFolioId()).isEqualTo("RES-B1");
    assertThat(link.getReason()).isEqualTo("reason");
    assertThat(link.getBookingRef()).isEqualTo("B1");
    assertThat(link.getCurrency()).isEqualTo("USD");
    assertThat(link.getAmountMinor()).isEqualTo(1_000);
    assertThat(link.amount()).isEqualTo(TEN);
    assertThat(link.getDueDate()).isEqualTo(DAY);

    link.cancel();
    assertThat(link.getStatus()).isEqualTo(PaymentLink.Status.CANCELLED);
    PaymentLink other = new PaymentLink("B2", "RES-B2", TEN, DAY, "r");
    other.supersede();
    assertThat(other.getStatus()).isEqualTo(PaymentLink.Status.SUPERSEDED);
  }

  @Test
  @DisplayName("A stored card keeps only what PCI allows, and when it was stored")
  void aStoredCardKeepsOnlyWhatPciAllowsAndWhenItWasStored() {
    CardToken card = new CardToken("tok_1", new byte[] {1}, new byte[] {2}, "k", new Pan("4111111111111111"), "12/29", Instant.EPOCH);
    assertThat(card.getToken()).isEqualTo("tok_1");
    assertThat(card.getSealedPan()).containsExactly(1);
    assertThat(card.getWrappedKey()).containsExactly(2);
    assertThat(card.getKeyId()).isEqualTo("k");
    assertThat(card.getExpiry()).isEqualTo("12/29");
    assertThat(card.getBin6()).isEqualTo("411111");
    assertThat(card.getLast4()).isEqualTo("1111");
    assertThat(card.getCreatedAt()).isNotNull();
  }
}
