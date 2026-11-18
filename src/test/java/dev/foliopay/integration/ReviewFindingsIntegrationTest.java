package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.foliopay.TestBase;
import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.GoneException;
import dev.foliopay.domain.model.BookingEvent;
import dev.foliopay.domain.model.BookingEvent.RatePlan;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.PaymentLink;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.service.BookingEventService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.Outcome;
import dev.foliopay.service.PaymentService.PaymentCommand;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** One test per substantiated finding of the security and idempotency review, run through the real database. */
@AutoConfigureMockMvc
class ReviewFindingsIntegrationTest extends TestBase {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 17);
  @Autowired BookingEventService bookings;
  @Autowired PaymentService payments;
  @Autowired ChargeRepository charges;
  @Autowired MockMvc mvc;

  private PaymentLink newLink(String ref) {
    var event = new BookingEvent(BookingEvent.Type.NEW, ref, "RES-" + ref, RatePlan.ADVANCE_PURCHASE, DAY.plusDays(10), new Money(80_000, "USD"));
    return bookings.handle(event, DAY).links().get(0);
  }

  private String secondCard() {
    return vault.tokenise(new Pan("4242424242424242"), "11/28").getToken();
  }

  @Test
  void aPaidLinkCannotBePaidAgainWithAnotherCard() {
    PaymentLink link = newLink("R1");
    String other = secondCard();
    assertThat(bookings.pay(link.getId(), token, DAY).outcome()).isEqualTo(Outcome.OK);

    assertThatThrownBy(() -> bookings.pay(link.getId(), other, DAY)).isInstanceOf(GoneException.class);

    assertThat(psp.captureCount()).as("one request, one charge").isEqualTo(1);
    assertThat(opera.postCount()).isEqualTo(1);
  }

  @Test
  void twoCardsRacingOnOneOpenLinkProduceExactlyOneCapture() throws Exception {
    PaymentLink link = newLink("R2");
    String other = secondCard();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Callable<String>> jobs = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String card = i % 2 == 0 ? token : other;
      jobs.add(() -> {
        try {
          return bookings.pay(link.getId(), card, DAY).outcome().name();
        } catch (ConflictException | GoneException refused) {
          return "REFUSED";
        }
      });
    }
    for (Future<String> done : pool.invokeAll(jobs)) {
      done.get();
    }
    pool.shutdown();

    assertThat(psp.captureCount()).as("only the card that won the claim was charged").isEqualTo(1);
    assertThat(opera.postCount()).isEqualTo(1);
  }

  @Test
  void theSameKeyWithADifferentFolioIsAConflictNotAnOkForTheOriginal() {
    String key = "k-" + UUID.randomUUID();
    payments.pay(PaymentCommand.bridge(key, "BK-A", "F-A", token, new Money(5_000, "USD"), DAY));

    assertThatThrownBy(() -> payments.pay(PaymentCommand.bridge(key, "BK-B", "F-B", token, new Money(5_000, "USD"), DAY.plusDays(1))))
        .isInstanceOf(ConflictException.class);
    assertThat(opera.postCount()).as("nothing was posted to the folio the client corrected to").isEqualTo(1);
  }

  @Test
  void aCaptureDeclinedAfterAuthorisationReleasesTheHold() {
    psp.arm(FakePsp.Fault.DECLINE_CAPTURE_NEXT);

    var result = payments.pay(PaymentCommand.bridge("k-" + UUID.randomUUID(), "BK-1", "F-1", token, new Money(5_000, "USD"), DAY));

    assertThat(result.outcome()).isEqualTo(Outcome.DECLINED);
    assertThat(psp.voidCount()).isEqualTo(1);
    assertThat(charges.findAll()).singleElement().satisfies(c -> assertThat(c.getStatus()).isEqualTo(Charge.Status.DECLINED));
  }

  @Test
  void linkChargeKeysNeverContainTheCardToken() throws Exception {
    PaymentLink link = newLink("R3");
    bookings.pay(link.getId(), token, DAY);

    String listing = mvc.perform(get("/api/v1/charges")).andReturn().getResponse().getContentAsString();
    assertThat(listing).contains("link:" + link.getId()).doesNotContain(token);
  }

  @Test
  void aRequestWithAForeignHostHeaderIsRefused() throws Exception {
    mvc.perform(get("/api/v1/charges").header("Host", "attacker.example")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/charges").header("Host", "127.0.0.1:8081")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/charges").header("Host", "localhost")).andExpect(status().isOk());
  }

  @Test
  void anOversizedBodyIsRefusedBeforeItIsParsed() throws Exception {
    String huge = "{\"mode\":\"clean\",\"csv\":\"" + "x".repeat(1_100_000) + "\"}";
    mvc.perform(post("/api/v1/reconcile").contentType(MediaType.APPLICATION_JSON).content(huge)).andExpect(status().isPayloadTooLarge());
  }

  @Test
  void aBusinessDateThatIsNotTheHotelsIsRefusedAndTheDefaultComesFromThePms() throws Exception {
    opera.setBusinessDate(LocalDate.of(2026, 11, 16)); // after midnight but before night audit
    String body = "{\"idempotencyKey\":\"bd-1\",\"bookingRef\":\"BK-1\",\"folioId\":\"F-1\",\"token\":\"" + token + "\",\"amountMinor\":500,\"currency\":\"USD\"%s}";

    mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(String.format(body, ",\"businessDate\":\"2026-11-17\"")))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(String.format(body, ""))).andExpect(status().isOk());
    assertThat(charges.findAll()).singleElement().satisfies(c -> assertThat(c.getBusinessDate()).isEqualTo(LocalDate.of(2026, 11, 16)));
  }

  @Test
  void aStoredUpstreamMessageIsBoundedAndSingleLine() {
    var result = payments.pay(PaymentCommand.bridge("k-" + UUID.randomUUID(), "BK-1", "F-CLOSED", token, new Money(5_000, "USD"), DAY));
    assertThat(result.posting().getLastError()).doesNotContain("\n").hasSizeLessThanOrEqualTo(255);
  }
}
