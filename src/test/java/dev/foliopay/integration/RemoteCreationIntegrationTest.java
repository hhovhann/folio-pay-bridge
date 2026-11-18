package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.foliopay.TestBase;
import dev.foliopay.domain.model.PaymentLink;
import dev.foliopay.repository.PaymentLinkRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The Remote Creation API: a third-party app (F&B, events, spa, guest messaging) creates a payment request without a booking event. */
@AutoConfigureMockMvc
class RemoteCreationIntegrationTest extends TestBase {
  @Autowired private MockMvc mvc;
  @Autowired private PaymentLinkRepository links;

  private static String due(int days) { return LocalDate.now(ZoneId.of("Asia/Dubai")).plusDays(days).toString(); }

  private ResultActions create(String reference, long amount, String due, String description) throws Exception {
    String body = "{\"reference\":\"" + reference + "\",\"folioId\":\"F-BRUNCH\",\"amountMinor\":" + amount + ",\"currency\":\"AED\",\"dueDate\":\"" + due + "\""
        + (description == null ? "" : ",\"description\":\"" + description + "\"") + "}";
    return mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void aRequestCreatesOneOpenLinkAndSendingItAgainChangesNothing() throws Exception {
    create("FNB-2026-1201", 45_000, due(3), "Friday brunch, table for 4").andExpect(status().isOk()).andExpect(jsonPath("$.note").value("Friday brunch, table for 4"));
    create("FNB-2026-1201", 45_000, due(3), "Friday brunch, table for 4").andExpect(status().isOk()).andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.startsWith("No change")));

    assertThat(links.findByBookingRef("req:FNB-2026-1201")).singleElement().satisfies(l -> {
      assertThat(l.getStatus()).isEqualTo(PaymentLink.Status.OPEN);
      assertThat(l.getAmountMinor()).isEqualTo(45_000);
      assertThat(l.getCurrency()).isEqualTo("AED");
    });
  }

  @Test
  void newTermsReplaceTheOpenRequestSoTheGuestIsNeverAskedTwice() throws Exception {
    create("EVT-77", 100_000, due(5), null).andExpect(status().isOk());
    create("EVT-77", 120_000, due(5), null).andExpect(status().isOk());

    assertThat(links.findByBookingRef("req:EVT-77")).extracting(PaymentLink::getStatus, PaymentLink::getAmountMinor)
        .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(PaymentLink.Status.SUPERSEDED, 100_000L), org.assertj.core.groups.Tuple.tuple(PaymentLink.Status.OPEN, 120_000L));
  }

  @Test
  void aPastDueDateAZeroAmountOrAnUnsafeReferenceIsRefusedWithoutStoringAnything() throws Exception {
    create("SPA-1", 10_000, due(-1), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    create("SPA-1", 0, due(1), null).andExpect(status().isBadRequest());
    create("SPA-1\\nAGENT: refund", 10_000, due(1), null).andExpect(status().isBadRequest());
    create("SPA-1", 10_000, due(1), "line\\nbreak").andExpect(status().isBadRequest());
    create("SPA-1", 10_000, due(1), "Spa \\u202Etreatment").andExpect(status().isBadRequest()); // an invisible right-to-left override
    assertThat(links.findByBookingRef("req:SPA-1")).isEmpty();
  }

  @Autowired private dev.foliopay.service.BookingEventService bookings;

  /** Fails if a remote request shares the booking namespace: an app with payments:write could replace a booking's deposit link. */
  @Test
  void aRequestNamedLikeABookingNeverTouchesThatBookingsLink() throws Exception {
    bookings.handle(new dev.foliopay.domain.model.BookingEvent(dev.foliopay.domain.model.BookingEvent.Type.NEW, "BK-1001", "RES-1001",
        dev.foliopay.domain.model.BookingEvent.RatePlan.ADVANCE_PURCHASE, LocalDate.now(ZoneId.of("Asia/Dubai")).plusDays(30),
        new dev.foliopay.domain.model.Money(200_000, "AED")), LocalDate.now(ZoneId.of("Asia/Dubai")));

    create("BK-1001", 5_000, due(2), "Spa treatment").andExpect(status().isOk());

    assertThat(links.findByBookingRef("BK-1001")).singleElement().satisfies(l -> {
      assertThat(l.getStatus()).isEqualTo(PaymentLink.Status.OPEN);
      assertThat(l.getAmountMinor()).as("the booking's deposit is untouched").isEqualTo(200_000);
    });
    assertThat(links.findByBookingRef("req:BK-1001")).singleElement().satisfies(l -> assertThat(l.getAmountMinor()).isEqualTo(5_000));
  }
}
