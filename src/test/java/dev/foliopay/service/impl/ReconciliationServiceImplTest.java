package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.foliopay.Fixtures;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.domain.model.ReconReport.Category;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.ReconciliationService.Period;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    charge.markCaptured(pspRef);
    captured.add(charge);
    if (postedToPms) {
      Posting posting = Fixtures.posting("post:" + charge.getId(), charge.getId(), "F-1", charge.amount(), LocalDate.of(2026, 11, 17));
      posting.markPosted("OP-1");
      posted.add(posting);
    }
    return charge;
  }

  /** The repositories answer from the lists above, as the database would. */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void database() {
    when(charges.findByStatus(Charge.Status.CAPTURED)).thenAnswer(call -> List.copyOf(captured));
    when(charges.findByStatusAndBusinessDateBetween(eq(Charge.Status.CAPTURED), any(), any())).thenAnswer(call -> captured.stream()
        .filter(c -> !c.getBusinessDate().isBefore(call.getArgument(1)) && !c.getBusinessDate().isAfter(call.getArgument(2))).toList());
    when(charges.findByStatusAndPspRefIn(eq(Charge.Status.CAPTURED), any())).thenAnswer(call -> captured.stream()
        .filter(c -> ((Collection<String>) call.getArgument(1)).contains(c.getPspRef())).toList());
    when(postings.findByChargeIdInAndStatus(any(), eq(Posting.Status.POSTED))).thenAnswer(call -> posted.stream()
        .filter(p -> ((Collection<String>) call.getArgument(0)).contains(p.getChargeId())).toList());
  }

  private ReconReport reconcile(SettlementLine... lines) {
    return service.reconcile(List.of(lines), Period.ALL);
  }

  @Test
  void aPastedStatementIsParsedAndReconciled() {
    capture("cap_1", 1_000, true);

    ReconReport report = service.reconcileCsv("psp_ref,amount_minor,currency\ncap_1,1000,USD\ncap_x,5,USD", Period.ALL);

    assertThat(report.matched()).isEqualTo(1);
    assertThat(report.exceptions()).containsOnlyKeys("UNKNOWN_IN_SETTLEMENT");
  }

  @Test
  void aCleanStatementMatchesEverythingWithNoExceptions() {
    capture("cap_1", 1_000, true);
    capture("cap_2", 2_000, true);

    ReconReport report = reconcile(new SettlementLine("cap_1", 1_000, "USD"), new SettlementLine("cap_2", 2_000, "USD"));

    assertThat(report.matched()).isEqualTo(2);
    assertThat(report.exceptions()).isEmpty();
    assertThat(usd(report).differenceMinor()).isZero();
  }

  @Test
  void aCaptureTheAcquirerNeverSettledIsMissing() {
    capture("cap_1", 1_000, true);
    ReconReport report = reconcile();
    assertThat(report.items()).singleElement().satisfies(i -> assertThat(i.category()).isEqualTo(Category.MISSING_IN_SETTLEMENT));
    assertThat(usd(report).differenceMinor()).isEqualTo(-1_000);
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
    assertThat(usd(report).differenceMinor()).as("the extra 1000 the guest was charged").isEqualTo(1_000);
  }

  @Test
  void aSettlementInAnotherCurrencyIsNotAMatchEvenWithTheSameNumber() {
    capture("cap_1", 10_000, true);
    ReconReport report = reconcile(new SettlementLine("cap_1", 10_000, "JPY"));
    assertThat(report.items()).singleElement().satisfies(i -> assertThat(i.category()).isEqualTo(Category.CURRENCY_DIFFERENCE));
  }

  private static ReconReport.Total usd(ReconReport report) { return report.totals().get("USD"); }

  @Test
  void totalsAreKeptPerCurrencySoYenAndDollarsAreNeverAddedUp() {
    capture("cap_1", 1_000, true);
    Charge yen = Fixtures.charge("k-yen", "BK-Y", "tok", new Money(100, "JPY"));
    yen.markCaptured("cap_yen");
    captured.add(yen);

    ReconReport report = reconcile(new SettlementLine("cap_1", 1_000, "USD"), new SettlementLine("cap_yen", 90, "JPY"));

    assertThat(report.totals()).containsOnlyKeys("JPY", "USD");
    assertThat(usd(report).differenceMinor()).isZero();
    assertThat(report.totals().get("JPY").differenceMinor()).isEqualTo(-10);
    assertThat(report.items()).singleElement().satisfies(i -> {
      assertThat(i.category()).isEqualTo(Category.AMOUNT_DIFFERENCE);
      assertThat(i.expectedCurrency()).isEqualTo("JPY");
      assertThat(i.settledCurrency()).isEqualTo("JPY");
    });
  }

  @Test
  void aCurrencyDifferenceShowsEachSideInItsOwnCurrency() {
    capture("cap_1", 10_000, true);
    ReconReport report = reconcile(new SettlementLine("cap_1", 10_000, "JPY"));
    assertThat(report.items()).singleElement().satisfies(i -> {
      assertThat(i.expectedCurrency()).isEqualTo("USD");
      assertThat(i.settledCurrency()).isEqualTo("JPY");
    });
    assertThat(usd(report).settledMinor()).as("yen settled is not dollars settled").isZero();
    assertThat(report.totals().get("JPY").settledMinor()).isEqualTo(10_000);
  }

  @Test
  void shadowRunsAreNotRevenueAndStayOutOfTheReport() {
    Charge shadow = Fixtures.charge("shadow-run1-BK-1", "BK-1", "tok", new Money(9_000, "USD"));
    shadow.markCaptured("cap_shadow");
    captured.add(shadow);
    capture("cap_real", 1_000, true);

    ReconReport report = reconcile(new SettlementLine("cap_real", 1_000, "USD"), new SettlementLine("cap_shadow", 9_000, "USD"));

    assertThat(report.matched()).isEqualTo(1);
    assertThat(report.exceptions()).isEmpty();
    assertThat(report.totals()).containsOnlyKeys("USD");
    assertThat(usd(report).expectedMinor()).isEqualTo(1_000);
    assertThat(usd(report).settledMinor()).isEqualTo(1_000);
  }

  @Test
  void aDistortedAcquirerStatementProducesOneOfEachSimulatedProblem() {
    capture("cap_a", 1_000, true);
    capture("cap_b", 2_000, true);
    capture("cap_c", 3_000, true);
    when(psp.settlementStatement()).thenReturn(List.of(new SettlementLine("cap_a", 1_000, "USD"), new SettlementLine("cap_b", 2_000, "USD"), new SettlementLine("cap_c", 3_000, "USD")));

    ReconReport report = service.reconcileAcquirerStatement(true, Period.ALL);

    assertThat(report.exceptions()).containsOnlyKeys("MISSING_IN_SETTLEMENT", "AMOUNT_DIFFERENCE", "UNKNOWN_IN_SETTLEMENT");
  }

  /** Fails if the whole history is expected in every file: from the second day on, every earlier capture would be reported missing. */
  @Test
  void onlyCapturesTakenInTheStatementsPeriodAreExpectedInIt() {
    Charge yesterday = capture("cap_old", 1_000, true);
    Charge today = capture("cap_new", 2_000, true);
    LocalDate day = today.getBusinessDate();
    setBusinessDate(yesterday, day.minusDays(1));

    ReconReport report = service.reconcile(List.of(new SettlementLine("cap_new", 2_000, "USD")), new Period(day, day));

    assertThat(report.exceptions()).as("yesterday's capture was settled in yesterday's file").isEmpty();
    assertThat(report.matched()).isEqualTo(1);
  }

  @Test
  void aLateSettlementOfAnOlderCaptureIsMatchedNotReportedUnknown() {
    Charge old = capture("cap_old", 1_000, true);
    LocalDate day = old.getBusinessDate();
    setBusinessDate(old, day.minusDays(3));

    ReconReport report = service.reconcile(List.of(new SettlementLine("cap_old", 1_000, "USD")), new Period(day, day));

    assertThat(report.matched()).isEqualTo(1);
    assertThat(report.exceptions()).isEmpty();
  }

  /** Fails if lines in two currencies are summed into one figure labelled with the first currency: an amount nobody ever settled. */
  @Test
  void aReferenceSettledInTwoCurrenciesGivesOneItemPerCurrencyNeverASumAcrossThem() {
    capture("cap_1", 1_000, true);

    ReconReport report = reconcile(new SettlementLine("cap_1", 1_000, "USD"), new SettlementLine("cap_1", 900, "EUR"));

    assertThat(report.items()).singleElement().satisfies(i -> {
      assertThat(i.category()).isEqualTo(Category.CURRENCY_DIFFERENCE);
      assertThat(i.settledCurrency()).isEqualTo("EUR");
      assertThat(i.settledMinor()).isEqualTo(900);
    });
    assertThat(report.matched()).as("one exception means not matched").isZero();
  }

  @Test
  void anUnknownReferenceInTwoCurrenciesIsReportedPerCurrency() {
    ReconReport report = reconcile(new SettlementLine("cap_x", 500, "USD"), new SettlementLine("cap_x", 700, "EUR"));
    assertThat(report.items()).extracting(ReconReport.Item::settledCurrency, ReconReport.Item::settledMinor).containsExactly(org.assertj.core.groups.Tuple.tuple("EUR", 700L), org.assertj.core.groups.Tuple.tuple("USD", 500L));
  }

  @Test
  void aPeriodThatEndsBeforeItStartsIsRefused() {
    assertThatThrownBy(() -> new Period(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 17))).isInstanceOf(InvalidRequestException.class);
  }

  private static void setBusinessDate(Charge charge, LocalDate day) {
    org.springframework.test.util.ReflectionTestUtils.setField(charge, "businessDate", day);
  }

  /** Fails if one IN list holds every id: PostgreSQL's driver refuses more than 65,535 bind parameters in one statement. */
  @Test
  void largeIdListsAreQueriedInSlicesOfAtMostAThousand() {
    for (int i = 0; i < 2_500; i++) capture("cap_" + i, 100, true);

    reconcile();

    ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
    verify(postings, times(3)).findByChargeIdInAndStatus(ids.capture(), eq(Posting.Status.POSTED));
    assertThat(ids.getAllValues()).allSatisfy(slice -> assertThat(slice).hasSizeLessThanOrEqualTo(ReconciliationServiceImpl.IN_LIST_CHUNK));
    assertThat(ids.getAllValues().stream().mapToInt(Collection::size).sum()).isEqualTo(2_500);
  }
}
