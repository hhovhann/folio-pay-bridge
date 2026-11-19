package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.TestBase;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.gateway.fake.FakeOpera;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.Outcome;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.PostingService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole stack (services, Hibernate, H2 with the real unique constraints and version columns) against the fake processor and PMS. These
 * are the tests that need a real database: what the constraints and the optimistic lock do under repeats, races and lost responses.
 */
@DisplayName("Payment flow: the whole stack against the fake processor and PMS")
class PaymentFlowIntegrationTest extends TestBase {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 17);
  @Autowired PaymentService payments;
  @Autowired PostingService postings;
  @Autowired ChargeRepository charges;
  @Autowired PostingRepository postingRepository;

  private static String newKey() { return "k-" + UUID.randomUUID(); }

  private PaymentService.Result pay(String key, String folio, long amount) { return pay(key, folio, amount, token); }

  private PaymentService.Result pay(String key, String folio, long amount, String cardToken) {
    return payments.pay(PaymentCommand.bridge(key, "BK-1", folio, cardToken, new Money(amount, "USD"), DAY));
  }

  @Test
  @DisplayName("A payment is charged once and posted once")
  void aPaymentIsChargedOnceAndPostedOnce() {
    var result = pay(newKey(), "F-100", 25_000);

    assertThat(result.outcome()).isEqualTo(Outcome.OK);
    assertThat(charges.findAll()).singleElement().satisfies(c -> assertThat(c.getStatus()).isEqualTo(Charge.Status.CAPTURED));
    assertThat(postingRepository.findAll()).singleElement().satisfies(p -> assertThat(p.getStatus()).isEqualTo(Posting.Status.POSTED));
    assertThat(psp.captureCount()).isEqualTo(1);
    assertThat(opera.postCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Repeating the request leaves one charge row and one posting row")
  void repeatingTheRequestLeavesOneChargeRowAndOnePostingRow() {
    String key = newKey();
    var first = pay(key, "F-100", 25_000);
    var second = pay(key, "F-100", 25_000);

    assertThat(second.charge().getId()).isEqualTo(first.charge().getId());
    assertThat(charges.count()).isEqualTo(1);
    assertThat(postingRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Parallel duplicates end with one charge row, one captured state and one posting row")
  void parallelDuplicatesEndWithOneChargeRowOneCapturedStateAndOnePostingRow() throws Exception {
    String key = newKey();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Callable<PaymentService.Result>> jobs = new ArrayList<>();
    for (int i = 0; i < 8; i++) jobs.add(() -> pay(key, "F-200", 31_000));
    for (Future<PaymentService.Result> done : pool.invokeAll(jobs)) done.get(); // none may fail
    pool.shutdown();

    // The fake processor also deduplicates by key, so this proves the database side: the unique key and the version column.
    assertThat(charges.findAll()).singleElement().satisfies(c -> {
      assertThat(c.getStatus()).as("a losing twin must not overwrite the winner's CAPTURED").isEqualTo(Charge.Status.CAPTURED);
      assertThat(c.getPspRef()).isNotNull();
    });
    assertThat(postingRepository.findAll()).singleElement().satisfies(p -> assertThat(p.getStatus()).isEqualTo(Posting.Status.POSTED));
    assertThat(opera.postCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("A lost response after capture is retried without charging twice")
  void aLostResponseAfterCaptureIsRetriedWithoutChargingTwice() {
    psp.arm(FakePsp.Fault.TIMEOUT_AFTER_CAPTURE);

    var result = pay(newKey(), "F-100", 18_000);

    assertThat(result.outcome()).isEqualTo(Outcome.OK);
    assertThat(psp.captureCount()).isEqualTo(1);
    assertThat(psp.capturedTotalMinor()).isEqualTo(18_000);
  }

  @Test
  @DisplayName("A lost response after the PMS post leaves one post on the folio")
  void aLostResponseAfterThePmsPostLeavesOnePostOnTheFolio() {
    opera.arm(FakeOpera.Fault.TIMEOUT_AFTER_POST);
    assertThat(pay(newKey(), "F-400", 22_000).outcome()).isEqualTo(Outcome.OK);
    assertThat(opera.postCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("A decline is stored as final and nothing reaches the PMS")
  void aDeclineIsStoredAsFinalAndNothingReachesThePms() {
    psp.arm(FakePsp.Fault.DECLINE_NEXT);

    var result = pay(newKey(), "F-100", 12_000);

    assertThat(result.outcome()).isEqualTo(Outcome.DECLINED);
    assertThat(postingRepository.count()).isZero();
    assertThat(psp.captureCount()).isZero();
  }

  @Test
  @DisplayName("An OPERA outage keeps the captured payment as a replayable posting that posts exactly once")
  void anOperaOutageKeepsTheCapturedPaymentAsAReplayablePostingThatPostsExactlyOnce() {
    opera.arm(FakeOpera.Fault.DOWN);
    var result = pay(newKey(), "F-300", 40_000);

    assertThat(result.outcome()).isEqualTo(Outcome.CHARGED_NOT_POSTED);
    assertThat(result.posting().getFailureCategory()).isEqualTo(Posting.FailureCategory.OPERA_UNAVAILABLE);
    assertThat(psp.captureCount()).as("money was taken, so the posting must survive").isEqualTo(1);

    opera.arm(FakeOpera.Fault.NONE);
    assertThat(postings.replay(result.posting().getId()).getStatus()).isEqualTo(Posting.Status.POSTED);
    postings.replay(result.posting().getId());
    assertThat(opera.postCount()).isEqualTo(1);
  }

  /**
   * Review finding H2. OPERA does not deduplicate, and "look, then post" is not atomic: two replays could both look, both find nothing and
   * both post. The lease lets one through; the others return the stored state. The fake counts every post, so a second one fails this test.
   */
  @Test
  @DisplayName("Parallel replays of one dead-lettered posting post it once")
  void parallelReplaysOfOneDeadLetteredPostingPostItOnce() throws Exception {
    opera.arm(FakeOpera.Fault.DOWN);
    String postingId = pay(newKey(), "F-400", 12_000).posting().getId();
    opera.arm(FakeOpera.Fault.NONE);

    ExecutorService pool = Executors.newFixedThreadPool(8);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    List<Callable<Posting>> jobs = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      jobs.add(() -> {
        start.await();
        return postings.replay(postingId);
      });
    }
    List<Future<Posting>> running = jobs.stream().map(pool::submit).toList();
    start.countDown();
    for (Future<Posting> done : running) done.get(); // none may fail
    pool.shutdown();

    assertThat(opera.postCount()).as("one payment, one folio entry").isEqualTo(1);
    assertThat(postingRepository.findById(postingId).orElseThrow().getStatus()).isEqualTo(Posting.Status.POSTED);
  }

  @Test
  @DisplayName("A closed folio is rejected, and replay leaves it alone even after the folio would accept")
  void aClosedFolioIsRejectedAndReplayLeavesItAloneEvenAfterTheFolioWouldAccept() {
    var result = pay(newKey(), "F-CLOSED", 9_900);
    assertThat(result.posting().getFailureCategory()).isEqualTo(Posting.FailureCategory.REJECTED_BY_OPERA);
    int attemptsBefore = result.posting().getAttempts();

    var replayed = postings.replay(result.posting().getId());

    assertThat(replayed.getStatus()).isEqualTo(Posting.Status.DEAD_LETTER);
    assertThat(replayed.getAttempts()).as("replay did not call OPERA again").isEqualTo(attemptsBefore);
  }

  @Test
  @DisplayName("Money is conserved across processor charges and postings")
  void moneyIsConservedAcrossProcessorChargesAndPostings() {
    long expected = 0;
    for (int i = 0; i < 20; i++) {
      long amount = 1_000 + i * 137L;
      pay(newKey(), "F-" + i, amount);
      expected += amount;
    }
    assertThat(psp.capturedTotalMinor()).isEqualTo(expected);
    assertThat(charges.findAll().stream().filter(c -> c.getStatus() == Charge.Status.CAPTURED).mapToLong(Charge::getAmountMinor).sum()).isEqualTo(expected);
    assertThat(postingRepository.findAll().stream().filter(Posting::isPosted).mapToLong(Posting::getAmountMinor).sum()).isEqualTo(expected);
  }

  @Test
  @DisplayName("A second card after a decline is a new attempt, not a cached decline")
  void aSecondCardAfterADeclineIsANewAttemptNotACachedDecline() {
    String other = vault.tokenise(new Pan("4242424242424242"), "11/28").getToken();
    psp.arm(FakePsp.Fault.DECLINE_NEXT);
    String key = newKey();

    assertThat(pay(key, "F-1", 5_000).outcome()).isEqualTo(Outcome.DECLINED);
    assertThat(pay(key + "-card2", "F-1", 5_000, other).outcome()).isEqualTo(Outcome.OK);
  }
}
