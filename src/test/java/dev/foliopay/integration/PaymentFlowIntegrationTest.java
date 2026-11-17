package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.TestBase;
import dev.foliopay.domain.model.Charge;
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
import dev.foliopay.domain.model.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole stack (services, Hibernate, H2 with the real unique constraints and version columns) against the fake processor and PMS. These
 * are the tests that need a real database: what the constraints and the optimistic lock do under repeats, races and lost responses.
 */
class PaymentFlowIntegrationTest extends TestBase {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 17);
  @Autowired PaymentService payments;
  @Autowired PostingService postings;
  @Autowired ChargeRepository charges;
  @Autowired PostingRepository postingRepository;

  private static String newKey() { return "k-" + UUID.randomUUID(); }

  private PaymentService.Result pay(String key, String folio, long amount) { return pay(key, folio, amount, token); }

  private PaymentService.Result pay(String key, String folio, long amount, String cardToken) {
    return payments.pay(new PaymentCommand(key, "BK-1", folio, cardToken, new Money(amount, "USD"), DAY));
  }

  @Test
  void aPaymentIsChargedOnceAndPostedOnce() {
    var result = pay(newKey(), "F-100", 25_000);

    assertThat(result.outcome()).isEqualTo(Outcome.OK);
    assertThat(charges.findAll()).singleElement().satisfies(c -> assertThat(c.getStatus()).isEqualTo(Charge.Status.CAPTURED));
    assertThat(postingRepository.findAll()).singleElement().satisfies(p -> assertThat(p.getStatus()).isEqualTo(Posting.Status.POSTED));
    assertThat(psp.captureCount()).isEqualTo(1);
    assertThat(opera.postCount()).isEqualTo(1);
  }

  @Test
  void repeatingTheRequestLeavesOneChargeRowAndOnePostingRow() {
    String key = newKey();
    var first = pay(key, "F-100", 25_000);
    var second = pay(key, "F-100", 25_000);

    assertThat(second.charge().getId()).isEqualTo(first.charge().getId());
    assertThat(charges.count()).isEqualTo(1);
    assertThat(postingRepository.count()).isEqualTo(1);
  }

  @Test
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
  void aLostResponseAfterCaptureIsRetriedWithoutChargingTwice() {
    psp.arm(FakePsp.Fault.TIMEOUT_AFTER_CAPTURE);

    var result = pay(newKey(), "F-100", 18_000);

    assertThat(result.outcome()).isEqualTo(Outcome.OK);
    assertThat(psp.captureCount()).isEqualTo(1);
    assertThat(psp.capturedTotalMinor()).isEqualTo(18_000);
  }

  @Test
  void aLostResponseAfterThePmsPostLeavesOnePostOnTheFolio() {
    opera.arm(FakeOpera.Fault.TIMEOUT_AFTER_POST);
    assertThat(pay(newKey(), "F-400", 22_000).outcome()).isEqualTo(Outcome.OK);
    assertThat(opera.postCount()).isEqualTo(1);
  }

  @Test
  void aDeclineIsStoredAsFinalAndNothingReachesThePms() {
    psp.arm(FakePsp.Fault.DECLINE_NEXT);

    var result = pay(newKey(), "F-100", 12_000);

    assertThat(result.outcome()).isEqualTo(Outcome.DECLINED);
    assertThat(postingRepository.count()).isZero();
    assertThat(psp.captureCount()).isZero();
  }

  @Test
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

  @Test
  void aClosedFolioIsRejectedAndReplayLeavesItAloneEvenAfterTheFolioWouldAccept() {
    var result = pay(newKey(), "F-CLOSED", 9_900);
    assertThat(result.posting().getFailureCategory()).isEqualTo(Posting.FailureCategory.REJECTED_BY_OPERA);
    int attemptsBefore = result.posting().getAttempts();

    var replayed = postings.replay(result.posting().getId());

    assertThat(replayed.getStatus()).isEqualTo(Posting.Status.DEAD_LETTER);
    assertThat(replayed.getAttempts()).as("replay did not call OPERA again").isEqualTo(attemptsBefore);
  }

  @Test
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
  void aSecondCardAfterADeclineIsANewAttemptNotACachedDecline() {
    String other = vault.tokenise(new Pan("4242424242424242"), "11/28").getToken();
    psp.arm(FakePsp.Fault.DECLINE_NEXT);
    String key = newKey();

    assertThat(pay(key, "F-1", 5_000).outcome()).isEqualTo(Outcome.DECLINED);
    assertThat(pay(key + "-card2", "F-1", 5_000, other).outcome()).isEqualTo(Outcome.OK);
  }
}
