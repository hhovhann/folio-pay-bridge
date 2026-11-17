package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import dev.foliopay.repository.PaymentLinkRepository;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.Outcome;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.support.Fingerprint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

class BookingEventServiceImplTest {
  private static final LocalDate TODAY = Fixtures.DAY;
  private static final String CARD_A = "tok_" + "a".repeat(32);
  private static final String CARD_B = "tok_" + "b".repeat(32);

  private final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
  private final PaymentService payments = mock(PaymentService.class);
  private final BookingEventServiceImpl service = new BookingEventServiceImpl(links, payments);
  private final List<PaymentLink> store = new ArrayList<>();

  @BeforeEach
  void setUp() {
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
    org.springframework.test.util.ReflectionTestUtils.setField(link, "status", PaymentLink.Status.PAYING);
    org.springframework.test.util.ReflectionTestUtils.setField(link, "payerFingerprint", Fingerprint.of(card));
    return link;
  }

  private Charge chargeWith(String status, PaymentLink link) {
    Charge charge = Fixtures.charge("k", "B1", CARD_A, link.amount());
    if (status.equals("CAPTURED")) charge.captured("cap_1");
    if (status.equals("DECLINED")) charge.declined("Do not honor");
    return charge;
  }

  @Test
  void aNewBookingCreatesOneOpenRequestFromThePolicy() {
    var outcome = service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    assertThat(outcome.links()).hasSize(1);
    assertThat(outcome.links().get(0).getAmountMinor()).isEqualTo(20_000);
    assertThat(outcome.links().get(0).isOpen()).isTrue();
    assertThat(outcome.refundReview()).isFalse();
  }

  @Test
  void aParallelTwinThatCreatesTheLiveLinkFirstIsTreatedAsTheRepeatItIs() {
    when(links.saveAndFlush(any(PaymentLink.class)))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("one live link per booking"))
        .thenAnswer(call -> add(call.getArgument(0)));

    var outcome = service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);

    assertThat(outcome.links()).hasSize(1);
  }

  @Test
  void aRepeatOfTheSameEventChangesNothing() {
    service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    var again = service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    assertThat(again.links()).hasSize(1);
    assertThat(again.note()).contains("No change");
  }

  @Test
  void aModifiedBookingSupersedesTheOpenRequestAndOpensAnother() {
    service.handle(event(Type.NEW, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    var out = service.handle(event(Type.MODIFIED, RatePlan.FLEXIBLE, 30, 150_000), TODAY);
    assertThat(out.links()).extracting(PaymentLink::getStatus).containsExactlyInAnyOrder(PaymentLink.Status.SUPERSEDED, PaymentLink.Status.OPEN);
  }

  @Test
  void aChangeWhileAPaymentIsInProgressIsNotAppliedSoTheGuestIsNotAskedToPayTwice() {
    claimedBy(CARD_A, openLink(20_000));
    var out = service.handle(event(Type.MODIFIED, RatePlan.FLEXIBLE, 30, 150_000), TODAY);
    assertThat(out.note()).contains("in progress");
    assertThat(out.links()).hasSize(1);
    verify(links, never()).saveAndFlush(any());
  }

  @Test
  void aChangeAfterPaymentNeedsAHumanNotANewRequest() {
    PaymentLink paid = openLink(20_000);
    org.springframework.test.util.ReflectionTestUtils.setField(paid, "status", PaymentLink.Status.PAID);
    var out = service.handle(event(Type.MODIFIED, RatePlan.FLEXIBLE, 30, 150_000), TODAY);
    assertThat(out.refundReview()).isTrue();
    assertThat(out.links()).hasSize(1);
    verify(links, never()).saveAndFlush(any());
  }

  @Test
  void cancellingBeforePaymentClosesTheRequest() {
    PaymentLink open = openLink(20_000);
    var out = service.handle(event(Type.CANCELLED, RatePlan.FLEXIBLE, 30, 100_000), TODAY);
    assertThat(open.getStatus()).isEqualTo(PaymentLink.Status.CANCELLED);
    assertThat(out.refundReview()).isFalse();
  }

  @Test
  void cancellingAfterPaymentFlagsARefundReview() {
    org.springframework.test.util.ReflectionTestUtils.setField(openLink(20_000), "status", PaymentLink.Status.PAID);
    assertThat(service.handle(event(Type.CANCELLED, RatePlan.FLEXIBLE, 30, 100_000), TODAY).refundReview()).isTrue();
  }

  @Test
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
  void aDeclinedCardGivesTheLinkBackForAnotherCard() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.DECLINED, chargeWith("DECLINED", link), null));

    service.pay(link.getId(), CARD_A, TODAY);

    verify(links).release(link.getId(), Fingerprint.of(CARD_A));
    verify(links, never()).complete(anyString(), anyString());
  }

  @Test
  void aPendingChargeKeepsTheClaimSoOnlyTheSameCardResumesIt() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.CHARGE_PENDING, chargeWith("PENDING", link), null));

    service.pay(link.getId(), CARD_A, TODAY);

    verify(links, never()).complete(anyString(), anyString());
    verify(links, never()).release(anyString(), anyString());
  }

  @Test
  void aSecondCardWhileAPaymentIsInProgressIsRefusedAndNeverCharged() {
    PaymentLink link = claimedBy(CARD_A, openLink(80_000));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_B, TODAY)).isInstanceOf(ConflictException.class).hasMessageContaining("in progress");
    verify(payments, never()).pay(any());
  }

  @Test
  void aSecondCardAfterTheLinkIsPaidIsRefusedAndNeverCharged() {
    PaymentLink link = openLink(80_000);
    org.springframework.test.util.ReflectionTestUtils.setField(link, "status", PaymentLink.Status.PAID);
    org.springframework.test.util.ReflectionTestUtils.setField(link, "payerFingerprint", Fingerprint.of(CARD_A));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_B, TODAY)).isInstanceOf(GoneException.class);
    verify(payments, never()).pay(any());
  }

  @Test
  void theSameCardPayingAPaidLinkAgainResumesTheSameIdempotentCharge() {
    PaymentLink link = openLink(80_000);
    org.springframework.test.util.ReflectionTestUtils.setField(link, "status", PaymentLink.Status.PAID);
    org.springframework.test.util.ReflectionTestUtils.setField(link, "payerFingerprint", Fingerprint.of(CARD_A));
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.OK, chargeWith("CAPTURED", link), null));

    service.pay(link.getId(), CARD_A, TODAY);

    ArgumentCaptor<PaymentCommand> sent = ArgumentCaptor.forClass(PaymentCommand.class);
    verify(payments).pay(sent.capture());
    assertThat(sent.getValue().idempotencyKey()).isEqualTo("link:" + link.getId() + ":" + Fingerprint.of(CARD_A));
  }

  @Test
  void losingTheClaimRaceMeansAnotherPaymentGotThereFirst() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(0);
    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(ConflictException.class);
    verify(payments, never()).pay(any());
  }

  @Test
  void aRequestRefusedBeforeAnyMoneyMovedGivesTheClaimBack() {
    PaymentLink link = openLink(80_000);
    when(links.claim(eq(link.getId()), anyString())).thenReturn(1);
    when(payments.pay(any())).thenThrow(new InvalidRequestException("Unknown card token"));

    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(InvalidRequestException.class);
    verify(links).release(link.getId(), Fingerprint.of(CARD_A));
  }

  @Test
  void aCancelledLinkCannotBePaid() {
    PaymentLink link = openLink(40_000);
    link.cancel();
    assertThatThrownBy(() -> service.pay(link.getId(), CARD_A, TODAY)).isInstanceOf(GoneException.class);
    verify(payments, never()).pay(any());
  }

  @Test
  void anUnknownLinkIsNotFound() {
    when(links.findById("lnk_x")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.pay("lnk_x", CARD_A, TODAY)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void theLinkListIsBounded() {
    Page<PaymentLink> page = new PageImpl<>(List.of());
    when(links.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(page);
    assertThat(service.findAll()).isEmpty();
  }
}
