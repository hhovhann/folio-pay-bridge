package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.Fixtures;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.domain.model.ReconReport.Category;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.repository.PostingRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationServiceImplTest {
  private final ChargeRepository charges = mock(ChargeRepository.class);
  private final PostingRepository postings = mock(PostingRepository.class);
  private final PspClient psp = mock(PspClient.class);
  private final ReconciliationServiceImpl service = new ReconciliationServiceImpl(charges, postings, psp);
  private final List<Charge> captured = new ArrayList<>();
  private final List<Posting> posted = new ArrayList<>();

  /** A captured charge, optionally posted to the folio. */
  private Charge capture(String pspRef, long amount, boolean postedToPms) {
    Charge charge = Fixtures.charge("key-" + pspRef, "BK-" + pspRef, "tok", new Money(amount, "USD"));
    charge.captured(pspRef);
    captured.add(charge);
    if (postedToPms) {
      Posting posting = Fixtures.posting("post:" + charge.getId(), charge.getId(), "F-1", charge.amount(), LocalDate.of(2026, 11, 17));
      posting.posted("OP-1");
      posted.add(posting);
    }
    return charge;
  }

  private ReconReport reconcile(SettlementLine... lines) {
    when(charges.findByStatus(Charge.Status.CAPTURED)).thenReturn(captured);
    when(postings.findByStatus(Posting.Status.POSTED)).thenReturn(posted);
    return service.reconcile(List.of(lines));
  }

  @Test
  void aCleanStatementMatchesEverythingWithNoExceptions() {
    capture("cap_1", 1_000, true);
    capture("cap_2", 2_000, true);

    ReconReport report = reconcile(new SettlementLine("cap_1", 1_000, "USD"), new SettlementLine("cap_2", 2_000, "USD"));

    assertThat(report.matched()).isEqualTo(2);
    assertThat(report.exceptions()).isEmpty();
    assertThat(report.differenceMinor()).isZero();
  }

  @Test
  void aCaptureTheAcquirerNeverSettledIsMissing() {
    capture("cap_1", 1_000, true);
    ReconReport report = reconcile();
    assertThat(report.items()).singleElement().satisfies(i -> assertThat(i.category()).isEqualTo(Category.MISSING_IN_SETTLEMENT));
    assertThat(report.differenceMinor()).isEqualTo(-1_000);
  }

  @Test
  void aShortSettlementIsAnAmountDifference() {
    capture("cap_1", 1_000, true);
    ReconReport report = reconcile(new SettlementLine("cap_1", 850, "USD"));
    assertThat(report.items()).singleElement().satisfies(i -> {
      assertThat(i.category()).isEqualTo(Category.AMOUNT_DIFFERENCE);
      assertThat(i.expectedMinor()).isEqualTo(1_000);
      assertThat(i.settledMinor()).isEqualTo(850);
    });
  }

  @Test
  void aLineWeNeverCapturedIsUnknown() {
    ReconReport report = reconcile(new SettlementLine("cap_ghost", 7_700, "USD"));
    assertThat(report.items()).singleElement().satisfies(i -> assertThat(i.category()).isEqualTo(Category.UNKNOWN_IN_SETTLEMENT));
  }

  @Test
  void aSettledButUnpostedCaptureIsFlaggedForThePms() {
    capture("cap_1", 1_000, false);
    ReconReport report = reconcile(new SettlementLine("cap_1", 1_000, "USD"));
    assertThat(report.items()).singleElement().satisfies(i -> assertThat(i.category()).isEqualTo(Category.NOT_POSTED_TO_PMS));
    assertThat(report.matched()).isZero();
  }

  @Test
  void aDoublePresentmentIsNotSilentlyDropped() {
    capture("cap_1", 1_000, true);
    ReconReport report = reconcile(new SettlementLine("cap_1", 1_000, "USD"), new SettlementLine("cap_1", 1_000, "USD"));
    assertThat(report.exceptions()).containsEntry("DUPLICATE_IN_SETTLEMENT", 1);
    assertThat(report.differenceMinor()).as("the extra 1000 the guest was charged").isEqualTo(1_000);
  }

  @Test
  void aSettlementInAnotherCurrencyIsNotAMatchEvenWithTheSameNumber() {
    capture("cap_1", 10_000, true);
    ReconReport report = reconcile(new SettlementLine("cap_1", 10_000, "JPY"));
    assertThat(report.items()).singleElement().satisfies(i -> assertThat(i.category()).isEqualTo(Category.CURRENCY_DIFFERENCE));
  }

  @Test
  void shadowRunsAreNotRevenueAndStayOutOfTheReport() {
    Charge shadow = Fixtures.charge("shadow-run1-BK-1", "BK-1", "tok", new Money(9_000, "USD"));
    shadow.captured("cap_shadow");
    captured.add(shadow);
    capture("cap_real", 1_000, true);

    ReconReport report = reconcile(new SettlementLine("cap_real", 1_000, "USD"), new SettlementLine("cap_shadow", 9_000, "USD"));

    assertThat(report.matched()).isEqualTo(1);
    assertThat(report.exceptions()).isEmpty();
    assertThat(report.expectedMinor()).isEqualTo(1_000);
    assertThat(report.settledMinor()).isEqualTo(1_000);
  }

  @Test
  void aDistortedAcquirerStatementProducesOneOfEachSimulatedProblem() {
    capture("cap_a", 1_000, true);
    capture("cap_b", 2_000, true);
    capture("cap_c", 3_000, true);
    when(psp.settlementStatement()).thenReturn(List.of(new SettlementLine("cap_a", 1_000, "USD"), new SettlementLine("cap_b", 2_000, "USD"), new SettlementLine("cap_c", 3_000, "USD")));
    when(charges.findByStatus(Charge.Status.CAPTURED)).thenReturn(captured);
    when(postings.findByStatus(Posting.Status.POSTED)).thenReturn(posted);

    ReconReport report = service.reconcileAcquirerStatement(true);

    assertThat(report.exceptions()).containsOnlyKeys("MISSING_IN_SETTLEMENT", "AMOUNT_DIFFERENCE", "UNKNOWN_IN_SETTLEMENT");
  }
}
