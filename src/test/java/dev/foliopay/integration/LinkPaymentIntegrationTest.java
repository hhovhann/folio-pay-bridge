package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.TestBase;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The payment-link product path through the real database: booking event, guest pays, link closes. */
class LinkPaymentIntegrationTest extends TestBase {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
  @Autowired BookingEventService bookings;
  @Autowired PaymentLinkRepository links;

  private PaymentLink newAdvanceLink(String ref, long total) {
    var event = new BookingEvent(BookingEvent.Type.NEW, ref, "F-" + ref, RatePlan.ADVANCE_PURCHASE, TODAY.plusDays(10), new Money(total, "USD"));
    return bookings.handle(event, TODAY).links().get(0);
  }

  @Test
  void payingALinkChargesPostsAndClosesItAndADoubleClickChargesOnce() {
    PaymentLink link = newAdvanceLink("B3", 80_000);

    assertThat(bookings.pay(link.getId(), token, TODAY).outcome()).isEqualTo(Outcome.OK);
    bookings.pay(link.getId(), token, TODAY);

    assertThat(links.findById(link.getId()).orElseThrow().getStatus()).isEqualTo(PaymentLink.Status.PAID);
    assertThat(psp.captureCount()).isEqualTo(1);
    assertThat(opera.postCount()).isEqualTo(1);
  }

  @Test
  void aLinkIsPaidOnCaptureEvenWhenOperaIsDownAndThePostingWaits() {
    opera.arm(FakeOpera.Fault.DOWN);
    PaymentLink link = newAdvanceLink("B6", 40_000);

    assertThat(bookings.pay(link.getId(), token, TODAY).outcome()).isEqualTo(Outcome.CHARGED_NOT_POSTED);
    assertThat(links.findById(link.getId()).orElseThrow().getStatus()).isEqualTo(PaymentLink.Status.PAID);
  }

  @Test
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
