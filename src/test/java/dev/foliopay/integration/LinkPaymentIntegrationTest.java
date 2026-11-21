package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.TestBase;
import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.model.BookingEvent;
import dev.foliopay.domain.model.BookingEvent.RatePlan;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.PaymentLink;
import dev.foliopay.gateway.fake.FakeOpera;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.repository.PaymentLinkRepository;
import dev.foliopay.service.BookingEventService;
import dev.foliopay.service.PaymentService.Outcome;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The payment-link product path through the real database: booking event, guest pays, link closes. */
@DisplayName("Payment links: the product path through the real database")
class LinkPaymentIntegrationTest extends TestBase {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
  @Autowired BookingEventService bookings;
  @Autowired PaymentLinkRepository links;

  private PaymentLink newAdvanceLink(String ref, long total) {
    var event = new BookingEvent(BookingEvent.Type.NEW, ref, "F-" + ref, RatePlan.ADVANCE_PURCHASE, TODAY.plusDays(10), new Money(total, "USD"));
    return bookings.handle(event, TODAY).links().get(0);
  }

  @Test
  @DisplayName("Paying a link charges, posts and closes it, and a double click charges once")
  void payingALinkChargesPostsAndClosesItAndADoubleClickChargesOnce() {
    PaymentLink link = newAdvanceLink("B3", 80_000);

    assertThat(bookings.pay(link.getId(), token, TODAY).outcome()).isEqualTo(Outcome.OK);
    bookings.pay(link.getId(), token, TODAY);

    assertThat(links.findById(link.getId()).orElseThrow().getStatus()).isEqualTo(PaymentLink.Status.PAID);
    assertThat(psp.captureCount()).isEqualTo(1);
    assertThat(opera.postCount()).isEqualTo(1);
  }

  /**
   * Review finding H1. The capture happened but every reply was lost, so the charge is PENDING and the link is held. Night audit then moves
   * the date. Before the fix the same card's retry was refused for the new date and the link was given back, so a second card could be
   * charged too. Now the retry (no date, as the console sends it) resumes the first charge on its own date, and the second card is refused.
   */
  @Test
  @DisplayName("A charge left pending before night audit is resumed after it, and no second card is charged")
  void aChargeLeftPendingBeforeNightAuditIsResumedAfterItAndNoSecondCardIsCharged() {
    opera.setBusinessDate(TODAY);
    PaymentLink link = newAdvanceLink("B8", 50_000);
    String otherCard = vault.tokenise(new Pan("4242424242424242"), "11/28").getToken();
    psp.arm(FakePsp.Fault.LOSE_CAPTURE_REPLIES);

    assertThat(bookings.pay(link.getId(), token, null).outcome()).isEqualTo(Outcome.CHARGE_PENDING);
    assertThat(psp.captureCount()).as("the money moved; the bridge just never heard").isEqualTo(1);

    opera.setBusinessDate(TODAY.plusDays(1)); // night audit
    psp.arm(FakePsp.Fault.NONE);

    assertThatThrownBy(() -> bookings.pay(link.getId(), otherCard, null)).isInstanceOf(ConflictException.class);
    var resumed = bookings.pay(link.getId(), token, null);

    assertThat(resumed.outcome()).isEqualTo(Outcome.OK);
    assertThat(resumed.charge().getBusinessDate()).as("the date it was taken on").isEqualTo(TODAY);
    assertThat(psp.captureCount()).as("one guest, one charge").isEqualTo(1);
    assertThat(links.findById(link.getId()).orElseThrow().getStatus()).isEqualTo(PaymentLink.Status.PAID);
  }

  /** Against the real database: the unique open-slot column, not the mocked exception of the unit test, keeps one live link per booking. */
  @Test
  @DisplayName("Parallel deliveries of one booking event leave one live link")
  void parallelDeliveriesOfOneBookingEventLeaveOneLiveLink() throws Exception {
    var event = new BookingEvent(BookingEvent.Type.NEW, "B9", "F-B9", RatePlan.FLEXIBLE, TODAY.plusDays(30), new Money(100_000, "USD"));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> running = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      running.add(pool.submit(() -> {
        start.await();
        return bookings.handle(event, TODAY);
      }));
    }
    start.countDown();
    for (var done : running) done.get(); // none may fail
    pool.shutdown();

    assertThat(links.findByBookingRef("B9")).as("every delivery after the first is a repeat").singleElement().satisfies(l -> assertThat(l.isOpen()).isTrue());
  }

  @Test
  @DisplayName("A link is paid on capture even when OPERA is down, and the posting waits")
  void aLinkIsPaidOnCaptureEvenWhenOperaIsDownAndThePostingWaits() {
    opera.arm(FakeOpera.Fault.DOWN);
    PaymentLink link = newAdvanceLink("B6", 40_000);

    assertThat(bookings.pay(link.getId(), token, TODAY).outcome()).isEqualTo(Outcome.CHARGED_NOT_POSTED);
    assertThat(links.findById(link.getId()).orElseThrow().getStatus()).isEqualTo(PaymentLink.Status.PAID);
  }

  @Test
  @DisplayName("A guest whose card is declined can pay the same link with another card")
  void aGuestWhoseCardIsDeclinedCanPayTheSameLinkWithAnotherCard() {
    PaymentLink link = newAdvanceLink("B7", 30_000);
    String otherCard = vault.tokenise(new Pan("4242424242424242"), "11/28").getToken();
    psp.arm(FakePsp.Fault.DECLINE_NEXT);

    assertThat(bookings.pay(link.getId(), token, TODAY).outcome()).isEqualTo(Outcome.DECLINED);
    assertThat(links.findById(link.getId()).orElseThrow().isOpen()).isTrue();
    assertThat(bookings.pay(link.getId(), otherCard, TODAY).outcome()).isEqualTo(Outcome.OK);
    assertThat(psp.captureCount()).isEqualTo(1);
  }
}
