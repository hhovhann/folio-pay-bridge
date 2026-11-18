package dev.foliopay.gateway.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.OperaClient.OperaPayment;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspTransientException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The in-memory stand-ins behave the way the real systems do where the bridge depends on it. */
class FakeGatewaysTest {
  private static final Money TEN = new Money(1_000, "USD");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 17);
  private final FakePsp psp = new FakePsp();
  private final FakeOpera opera = new FakeOpera(Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC));

  private OperaPayment payment(String reservation, String ref, String method, long cashier) {
    return new OperaPayment("H1", reservation, 1, method, cashier, TEN, ref, "cap_1");
  }

  @Test
  void theProcessorDeduplicatesByKeyAndCountsEachCaptureOnce() {
    var first = psp.capture("k:cap", "auth", TEN);
    var again = psp.capture("k:cap", "auth", TEN);
    assertThat(again).isEqualTo(first);
    assertThat(psp.captureCount()).isEqualTo(1);
    assertThat(psp.capturedTotalMinor()).isEqualTo(1_000);
  }

  @Test
  void theProcessorCanDeclineAnAuthorisationOrACaptureOnce() {
    psp.arm(FakePsp.Fault.DECLINE_NEXT);
    assertThat(psp.armed()).isEqualTo(FakePsp.Fault.DECLINE_NEXT);
    assertThatThrownBy(() -> psp.authorize("a", new Pan("4111111111111111"), "12/29", TEN)).isInstanceOf(PspDeclinedException.class);
    assertThat(psp.authorize("a", new Pan("4111111111111111"), "12/29", TEN).ref()).startsWith("auth_");

    psp.arm(FakePsp.Fault.DECLINE_CAPTURE_NEXT);
    assertThatThrownBy(() -> psp.capture("c", "auth", TEN)).isInstanceOf(PspDeclinedException.class);
    assertThat(psp.captureCount()).isZero();
  }

  @Test
  void theProcessorCanCaptureAndThenLoseTheReplyOnce() {
    psp.arm(FakePsp.Fault.TIMEOUT_AFTER_CAPTURE);
    assertThatThrownBy(() -> psp.capture("c", "auth", TEN)).isInstanceOf(PspTransientException.class);
    assertThat(psp.captureCount()).as("the money was taken").isEqualTo(1);
    assertThat(psp.capture("c", "auth", TEN).ref()).startsWith("cap_");
    assertThat(psp.captureCount()).isEqualTo(1);
  }

  @Test
  void theSettlementStatementListsCapturesInReferenceOrderWithTheirCurrency() {
    psp.capture("b", "auth", new Money(200, "EUR"));
    psp.capture("a", "auth", TEN);
    var lines = psp.settlementStatement();
    assertThat(lines).hasSize(2).extracting(SettlementLine::pspRef).isSorted();
    assertThat(lines).extracting(SettlementLine::currency).containsExactlyInAnyOrder("USD", "EUR");
  }

  @Test
  void voidingIsCountedAndResetClearsEverything() {
    psp.voidAuthorization("v", "auth_1");
    psp.capture("c", "auth", TEN);
    assertThat(psp.voidCount()).isEqualTo(1);
    psp.arm(FakePsp.Fault.DECLINE_NEXT);

    psp.reset();

    assertThat(psp.voidCount()).isZero();
    assertThat(psp.captureCount()).isZero();
    assertThat(psp.settlementStatement()).isEmpty();
    assertThat(psp.armed()).isEqualTo(FakePsp.Fault.NONE);
  }

  @Test
  void operaPostsPaymentsAndFindsThemByReferenceAndReportsTheClockDateAsItsBusinessDate() {
    String ref = opera.postPayment(payment("RES-1", "post:1", "CC", 9001));
    assertThat(ref).startsWith("OP-");
    assertThat(opera.find("H1", "RES-1", "post:1")).isPresent().get().satisfies(p -> assertThat(p.businessDate()).as("posted on OPERA's own date").isEqualTo(DAY));
    assertThat(opera.find("H1", "RES-1", "other")).isEmpty();
    assertThat(opera.find("H1", "RES-2", "post:1")).as("on another reservation's folio").isEmpty();
    assertThat(opera.postCount()).isEqualTo(1);
    assertThat(opera.businessDate("H1")).isEqualTo(LocalDate.of(2026, 11, 17));
  }

  @Test
  void operaBusinessDateCanBeSetLikeANightAuditAndResetBack() {
    opera.setBusinessDate(DAY.minusDays(1));
    assertThat(opera.businessDate("H1")).isEqualTo(DAY.minusDays(1));
    opera.reset();
    assertThat(opera.businessDate("H1")).isEqualTo(DAY);
  }

  @Test
  void operaRejectsAClosedFolioAndAPaymentWithoutACodeOrCashier() {
    assertThatThrownBy(() -> opera.postPayment(payment("F-CLOSED", "r1", "CC", 9001))).isInstanceOf(OperaRejectedException.class).hasMessageContaining("closed");
    assertThatThrownBy(() -> opera.postPayment(payment("RES-1", "r2", "", 9001))).isInstanceOf(OperaRejectedException.class);
    assertThatThrownBy(() -> opera.postPayment(payment("RES-1", "r3", "CC", 0))).isInstanceOf(OperaRejectedException.class);
    assertThat(opera.postCount()).isZero();
  }

  @Test
  void operaCanBeDownOrPostAndThenLoseTheReply() {
    opera.arm(FakeOpera.Fault.DOWN);
    assertThat(opera.armed()).isEqualTo(FakeOpera.Fault.DOWN);
    assertThatThrownBy(() -> opera.postPayment(payment("RES-1", "r1", "CC", 9001))).isInstanceOf(OperaUnavailableException.class);

    opera.arm(FakeOpera.Fault.TIMEOUT_AFTER_POST);
    assertThatThrownBy(() -> opera.postPayment(payment("RES-1", "r1", "CC", 9001))).isInstanceOf(OperaUnavailableException.class);
    assertThat(opera.postCount()).as("it posted before the reply was lost").isEqualTo(1);
    assertThat(opera.armed()).isEqualTo(FakeOpera.Fault.NONE);
  }
}
