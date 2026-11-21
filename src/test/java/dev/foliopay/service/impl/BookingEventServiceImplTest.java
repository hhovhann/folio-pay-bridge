package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.foliopay.Fixtures;
import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.GoneException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.BookingEvent;
import dev.foliopay.domain.model.BookingEvent.RatePlan;
import dev.foliopay.domain.model.BookingEvent.Type;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.PaymentLink;
import dev.foliopay.domain.rule.PaymentRequestPolicy;
import dev.foliopay.repository.PaymentLinkRepository;
import dev.foliopay.service.BookingEventService;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.Outcome;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.support.Fingerprint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Booking events and payment links: requests created, replaced, paid and cancelled")
class BookingEventServiceImplTest {
  private static final LocalDate TODAY = Fixtures.DAY;
  private static final String CARD_A = "tok_" + "a".repeat(32);
  private static final String CARD_B = "tok_" + "b".repeat(32);

  private final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
  private final PaymentService payments = mock(PaymentService.class);
  private final ChargeService charges = mock(ChargeService.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final AtomicBoolean inTransaction = new AtomicBoolean();
  private final BookingEventServiceImpl service = new BookingEventServiceImpl(links, payments, charges, tx, new PaymentRequestPolicy(20, 7));
  private final List<PaymentLink> store = new ArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    doAnswer(call -> {
      inTransaction.set(true);
      try {
        ((Consumer<TransactionStatus>) call.getArgument(0)).accept(null);
      } finally {
        inTransaction.set(false);
      }
      return null;
    }).when(tx).executeWithoutResult(any());
    when(links.findByBookingRef("B1")).thenAnswer(call -> List.copyOf(store));
    when(links.save(any(PaymentLink.class))).thenAnswer(call -> add(call.getArgument(0)));
    when(links.saveAndFlush(any(PaymentLink.class))).thenAnswer(call -> add(call.getArgument(0)));
  }

  private PaymentLink add(PaymentLink link) {
    if (!store.contains(link)) store.add(link);
    return link;
  }

  private static BookingEvent event(Type type, RatePlan plan, int arrivalInDays, long total) {
    return new BookingEvent(type, "B1", "RES-B1", plan, TODAY.plusDays(arrivalInDays), new Money(total, "USD"));
  }

  private PaymentLink openLink(long amount) {
    PaymentLink link = new PaymentLink("B1", "RES-B1", new Money(amount, "USD"), TODAY.plusDays(23), "reason");
    store.add(link);
    when(links.findById(link.getId())).thenReturn(Optional.of(link));
    return link;
  }

  /** A link that is already claimed (PAYING) by the given card, the way the atomic claim leaves it. */
  private PaymentLink claimedBy(String card, PaymentLink link) {
    ReflectionTestUtils.setField(link, "status", PaymentLink.Status.PAYING);
    ReflectionTestUtils.setField(link, "payerFingerprint", Fingerprint.of(card));
    return link;
  }

  private Charge chargeWith(String status, PaymentLink link) {
    Charge charge = Fixtures.charge("k", "B1", CARD_A, link.amount());
    if (status.equals("CAPTURED")) charge.markCaptured("cap_1");
    if (status.equals("DECLINED")) charge.markDeclined("Do not honor");
    return charge;
  }

  @Test
  @DisplayName("A new booking creates one open payment request from the policy")
  void aNewBookingCreatesOneOpenRequestFromThePolicy() {
    var outcome = service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    assertThat(outcome.links()).hasSize(1);
    assertThat(outcome.links().get(0).getAmountMinor()).isEqualTo(20_000);
    assertThat(outcome.links().get(0).isOpen()).isTrue();
    assertThat(outcome.refundReview()).isFalse();
  }

  @Test
  @DisplayName("A parallel twin that creates the live link first is treated as the repeat it is")
  void aParallelTwinThatCreatesTheLiveLinkFirstIsTreatedAsTheRepeatItIs() {
    when(links.saveAndFlush(any(PaymentLink.class)))
        .thenThrow(new DataIntegrityViolationException("one live link per booking"))
        .thenAnswer(call -> add(call.getArgument(0)));

    var outcome = service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);

    assertThat(outcome.links()).hasSize(1);
  }

  @Test
  @DisplayName("A repeat of the same booking event changes nothing")
  void aRepeatOfTheSameEventChangesNothing() {
    service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    var again = service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    assertThat(again.links()).hasSize(1);
    assertThat(again.note()).contains("No change");
  }

  @Test
  @DisplayName("A modified booking supersedes the open request and opens another")
  void aModifiedBookingSupersedesTheOpenRequestAndOpensAnother() {
    service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    var out = service.handle(event(Type.MODIFIED, RatePlan.FLEXIBLE, 30, 150_000), TODAY);
    assertThat(out.links()).extracting(PaymentLink::getStatus).containsExactlyInAnyOrder(PaymentLink.Status.SUPERSEDED, PaymentLink.Status.OPEN);
  }

  @Test
  @DisplayName("A change while a payment is in progress is not applied, so the guest is not asked to pay twice")
  void aChangeWhileAPaymentIsInProgressIsNotAppliedSoTheGuestIsNotAskedToPayTwice() {
    claimedBy(CARD_A, openLink(20_000));
    var out = service.handle(event(Type.MODIFIED, RatePlan.FLEXIBLE, 30, 150_000), TODAY);
    assertThat(out.note()).contains("in progress");
    assertThat(out.links()).hasSize(1);
    verify(links, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("A change after payment needs a person, not a new request")
  void aChangeAfterPaymentNeedsAHumanNotANewRequest() {
    PaymentLink paid = openLink(20_000);
    ReflectionTestUtils.setField(paid, "status", PaymentLink.Status.PAID);
    var out = service.handle(event(Type.MODIFIED, RatePlan.FLEXIBLE, 30, 150_000), TODAY);
    assertThat(out.refundReview()).isTrue();
    assertThat(out.links()).hasSize(1);
    verify(links, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("Cancelling before payment closes the request")
  void cancellingBeforePaymentClosesTheRequest() {
    PaymentLink open = openLink(20_000);
    var out = service.handle(event(Type.CANCELLED, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    assertThat(open.getStatus()).isEqualTo(PaymentLink.Status.CANCELLED);
    assertThat(out.refundReview()).isFalse();
  }

  @Test
  @DisplayName("Cancelling after payment flags a refund review")
  void cancellingAfterPaymentFlagsARefundReview() {
    ReflectionTestUtils.setField(openLink(20_000), "status", PaymentLink.Status.PAID);
    assertThat(service.handle(event(Type.CANCELLED, RatePlan.FLEXIBLE, 30, 100_000), TODAY).refundReview()).isTrue();
  }

  @Test
  @DisplayName("Paying claims the link, charges with a key that holds no token, and closes the link on capture")
  void payingClaimsTheLinkChargesWithAKeyThatHoldsNoTokenAndClosesItOnCapture() {
    PaymentLink link = openLink(80_000);
    when(links.claim(link.getId(), Fingerprint.of(CARD_A))).thenReturn(1);
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.OK, chargeWith("CAPTURED", link), null));

    service.pay(link.getId(), CARD_A, TODAY);

    ArgumentCaptor<PaymentCommand> sent = ArgumentCaptor.forClass(PaymentCommand.class);
    verify(payments).pay(sent.capture());
    assertThat(sent.getValue().idempotencyKey()).isEqualTo("link:" + link.getId() + ":" + Fingerprint.of(CARD_A)).doesNotContain(CARD_A);
    assertThat(sent.getValue().amount()).isEqualTo(new Money(80_000, "USD"));
    verify(links).complete(link.getId(), Fingerprint.of(CARD_A));
  }

  @Test
  @DisplayName("A declined card gives the link back for another card")
  void aDeclinedCardGivesTheLinkBackForAnotherCard() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.DECLINED, chargeWith("DECLINED", link), null));

    service.pay(link.getId(), CARD_A, TODAY);

    verify(links).release(link.getId(), Fingerprint.of(CARD_A));
    verify(links, never()).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("A pending charge keeps the claim, so only the same card resumes it")
  void aPendingChargeKeepsTheClaimSoOnlyTheSameCardResumesIt() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.CHARGE_PENDING, chargeWith("PENDING", link), null));

    service.pay(link.getId(), CARD_A, TODAY);

    verify(links, never()).complete(anyString(), anyString());
    verify(links, never()).release(anyString(), anyString());
  }

  @Test
  @DisplayName("A second card while a payment is in progress is refused and never charged")
  void aSecondCardWhileAPaymentIsInProgressIsRefusedAndNeverCharged() {
    PaymentLink link = claimedBy(CARD_A, openLink(80_000));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_B, TODAY)).isInstanceOf(ConflictException.class).hasMessageContaining("in progress");
    verify(payments, never()).pay(any());
  }

  @Test
  @DisplayName("A second card after the link is paid is refused and never charged")
  void aSecondCardAfterTheLinkIsPaidIsRefusedAndNeverCharged() {
    PaymentLink link = openLink(80_000);
    ReflectionTestUtils.setField(link, "status", PaymentLink.Status.PAID);
    ReflectionTestUtils.setField(link, "payerFingerprint", Fingerprint.of(CARD_A));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_B, TODAY)).isInstanceOf(GoneException.class);
    verify(payments, never()).pay(any());
  }

  @Test
  @DisplayName("The same card paying a paid link again resumes the same idempotent charge")
  void theSameCardPayingAPaidLinkAgainResumesTheSameIdempotentCharge() {
    PaymentLink link = openLink(80_000);
    ReflectionTestUtils.setField(link, "status", PaymentLink.Status.PAID);
    ReflectionTestUtils.setField(link, "payerFingerprint", Fingerprint.of(CARD_A));
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.OK, chargeWith("CAPTURED", link), null));

    service.pay(link.getId(), CARD_A, TODAY);

    ArgumentCaptor<PaymentCommand> sent = ArgumentCaptor.forClass(PaymentCommand.class);
    verify(payments).pay(sent.capture());
    assertThat(sent.getValue().idempotencyKey()).isEqualTo("link:" + link.getId() + ":" + Fingerprint.of(CARD_A));
  }

  @Test
  @DisplayName("Losing the claim race means another payment got there first")
  void losingTheClaimRaceMeansAnotherPaymentGotThereFirst() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(0);
    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(ConflictException.class);
    verify(payments, never()).pay(any());
  }

  @Test
  @DisplayName("A request refused before any charge existed gives the claim back")
  void aRequestRefusedBeforeAnyChargeExistedGivesTheClaimBack() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenThrow(new InvalidRequestException("Unknown card token"));
    when(charges.find(anyString(), anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(InvalidRequestException.class);
    verify(links).release(link.getId(), Fingerprint.of(CARD_A));
  }

  /** The bug this replaces: any refusal released the claim, even when a pending charge (money that may have moved) already existed. */
  @Test
  @DisplayName("A refusal after a pending charge exists keeps the claim, so no second card can be charged")
  void aRefusalAfterAPendingChargeExistsKeepsTheClaimSoNoSecondCardCanBeCharged() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenThrow(new ConflictException("Idempotency key was already used with different parameters"));
    when(charges.find(Charge.BRIDGE_CLIENT, "link:" + link.getId() + ":" + Fingerprint.of(CARD_A))).thenReturn(Optional.of(chargeWith("PENDING", link)));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(ConflictException.class);
    verify(links, never()).release(anyString(), anyString());
    verify(links, never()).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("A failure after the capture still closes the link")
  void aFailureAfterTheCaptureStillClosesTheLink() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenThrow(new IllegalStateException("posting blew up after the capture"));
    when(charges.find(anyString(), anyString())).thenReturn(Optional.of(chargeWith("CAPTURED", link)));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(IllegalStateException.class);
    verify(links).complete(link.getId(), Fingerprint.of(CARD_A));
    verify(links, never()).release(anyString(), anyString());
  }

  @Test
  @DisplayName("A failure after a decline gives the link back")
  void aFailureAfterADeclineGivesTheLinkBack() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenThrow(new IllegalStateException("failed after the decline was stored"));
    when(charges.find(anyString(), anyString())).thenReturn(Optional.of(chargeWith("DECLINED", link)));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(IllegalStateException.class);
    verify(links).release(link.getId(), Fingerprint.of(CARD_A));
  }

  @Test
  @DisplayName("When the charge cannot even be read, the claim is kept, because that can never charge twice")
  void whenTheChargeCannotEvenBeReadTheClaimIsKeptBecauseThatCanNeverChargeTwice() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenThrow(new IllegalStateException("database gone"));
    when(charges.find(anyString(), anyString())).thenThrow(new IllegalStateException("database gone"));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(IllegalStateException.class);
    verify(links, never()).release(anyString(), anyString());
    verify(links, never()).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("A cancelled link cannot be paid")
  void aCancelledLinkCannotBePaid() {
    PaymentLink link = openLink(40_000);
    link.cancel();
    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(GoneException.class);
    verify(payments, never()).pay(any());
  }

  @Test
  @DisplayName("An unknown link is not found")
  void anUnknownLinkIsNotFound() {
    when(links.findById("lnk_x")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.pay("lnk_x", CARD_A, TODAY)).isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("The link list is bounded")
  void theLinkListIsBounded() {
    Page<PaymentLink> page = new PageImpl<>(List.of());
    when(links.findAll(any(Pageable.class))).thenReturn(page);
    assertThat(service.findAll()).isEmpty();
    ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
    verify(links).findAll(asked.capture());
    assertThat(asked.getValue().getPageSize()).isEqualTo(500);
    verify(links, never()).findAll();
  }

  /** Fails if the old link is superseded outside the transaction that stores the new one: a failed insert would leave no live link. */
  @Test
  @DisplayName("Superseding the old link and storing the new one happen in one transaction")
  void supersedingTheOldLinkAndStoringTheNewOneHappenInOneTransaction() {
    service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    List<Boolean> writesInTransaction = new ArrayList<>();
    when(links.saveAndFlush(any(PaymentLink.class))).thenAnswer(call -> {
      writesInTransaction.add(inTransaction.get());
      return add(call.getArgument(0));
    });

    service.handle(event(Type.MODIFIED, RatePlan.FLEXIBLE, 30, 150_000), TODAY);

    assertThat(writesInTransaction).as("the superseded link and the new one").containsExactly(true, true);
  }

  /** The money is taken; a link whose state cannot be closed must not turn the guest's successful payment into an error. */
  @Test
  @DisplayName("A capture is returned even when the link can no longer be closed by this card")
  void aCaptureIsReturnedEvenWhenTheLinkCanNoLongerBeClosedByThisCard() {
    PaymentLink link = openLink(80_000);
    when(links.claim(link.getId(), Fingerprint.of(CARD_A))).thenReturn(1);
    when(links.complete(link.getId(), Fingerprint.of(CARD_A))).thenReturn(0);
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.OK, chargeWith("CAPTURED", link), null));

    assertThat(service.pay(link.getId(), CARD_A, TODAY).outcome()).isEqualTo(Outcome.OK);
  }

  private static BookingEventService.PaymentRequest remote(long amount, String description) {
    return new BookingEventService.PaymentRequest("B1", "RES-B1", new Money(amount, "USD"), TODAY.plusDays(2), description);
  }

  @Test
  @DisplayName("A remote request without a description gets a plain one")
  void aRemoteRequestWithoutADescriptionGetsAPlainOne() {
    var out = service.request(remote(5_000, " "), TODAY);
    assertThat(out.note()).isEqualTo("Payment request");
    assertThat(store).singleElement().satisfies(l -> assertThat(l.getReason()).isEqualTo("Payment request"));
  }

  @Test
  @DisplayName("A remote request does not touch a link that is being paid or is paid")
  void aRemoteRequestDoesNotTouchALinkThatIsBeingPaidOrIsPaid() {
    PaymentLink link = claimedBy(CARD_A, openLink(80_000));
    when(links.findByBookingRef("req:B1")).thenReturn(List.of(link));
    assertThat(service.request(remote(9_000, null), TODAY).note()).startsWith("A payment is in progress");
    ReflectionTestUtils.setField(link, "status", PaymentLink.Status.PAID);
    assertThat(service.request(remote(9_000, null), TODAY).note()).startsWith("Already paid");
    verify(links, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("A remote request due in the past or for nothing is refused")
  void aRemoteRequestDueInThePastOrForNothingIsRefused() {
    assertThatThrownBy(() -> service.request(new BookingEventService.PaymentRequest("B1", "RES-B1", new Money(5_000, "USD"), TODAY.minusDays(1), null), TODAY))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.request(remote(0, null), TODAY)).isInstanceOf(InvalidRequestException.class);
  }

  /** Fails if a twin that superseded the open link first makes the same request a 409: it is the repeat, so "no change". */
  @Test
  @DisplayName("A twin that superseded the open link first makes this request the repeat")
  void aTwinThatSupersededTheOpenLinkFirstMakesThisRequestTheRepeat() {
    PaymentLink old = openLink(1_000);
    PaymentLink twins = new PaymentLink("req:B1", "RES-B1", new Money(5_000, "USD"), TODAY.plusDays(2), "Payment request");
    when(links.findByBookingRef("req:B1")).thenReturn(List.of(old)).thenReturn(List.of(twins));
    when(links.saveAndFlush(any(PaymentLink.class))).thenThrow(new ObjectOptimisticLockingFailureException(PaymentLink.class, old.getId()));

    assertThat(service.request(remote(5_000, null), TODAY).note()).startsWith("No change");
  }
}
