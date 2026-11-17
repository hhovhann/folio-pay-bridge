package dev.foliopay.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.GoneException;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.Fixtures;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.Outcome;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.PostingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP contract only: services are mocked, so this proves validation, status mapping, safe errors and what the JSON exposes. */
@WebMvcTest(PaymentController.class)
@Import(PaymentControllerTest.FixedClock.class)
class PaymentControllerTest {
  @TestConfiguration
  static class FixedClock {
    @Bean
    Clock clock() { return Clock.fixed(Instant.parse("2026-11-17T22:30:00Z"), ZoneId.of("Asia/Dubai")); }
  }

  @Autowired MockMvc mvc;
  @MockitoBean PaymentService payments;
  @MockitoBean ChargeService charges;
  @MockitoBean PostingService postings;
  @MockitoBean dev.foliopay.service.BusinessDateService businessDates;

  private static final String TOKEN = "tok_" + "c".repeat(32);
  private static final String VALID =
      "{\"idempotencyKey\":\"k-1\",\"bookingRef\":\"BK-1\",\"folioId\":\"F-1\",\"token\":\"" + TOKEN + "\",\"amountMinor\":12345,\"currency\":\"USD\"}";

  private ResultActions pay(String body) throws Exception {
    return mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void aValidPaymentIsPassedToTheServiceWithTheBusinessDateTheServiceResolves() throws Exception {
    when(businessDates.resolve(any())).thenReturn(LocalDate.of(2026, 11, 17));
    Charge charge = Fixtures.charge("k-1", "BK-1", "tok_secret_token", new Money(12_345, "USD"));
    charge.captured("cap_1");
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.CHARGE_PENDING, charge, null));

    String json = pay(VALID).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("CHARGE_PENDING"))
        .andReturn().getResponse().getContentAsString();

    ArgumentCaptor<PaymentCommand> sent = ArgumentCaptor.forClass(PaymentCommand.class);
    Mockito.verify(payments).pay(sent.capture());
    assertThat(sent.getValue().businessDate()).as("the PMS business date, not the wall clock").isEqualTo(LocalDate.of(2026, 11, 17));
    assertThat(sent.getValue().amount()).isEqualTo(new Money(12_345, "USD"));
    assertThat(json).as("the card token is not part of the response").doesNotContain("tok_secret_token").doesNotContain("cardToken");
  }

  @Test
  void invalidInputIsRejectedBeforeAnyServiceIsCalled() throws Exception {
    pay("{\"idempotencyKey\":\"x\"}").andExpect(status().isBadRequest());
    pay(VALID.replace("12345", "0")).andExpect(status().isBadRequest());
    pay(VALID.replace("12345", "1000000000001")).andExpect(status().isBadRequest());
    pay(VALID.replace("BK-1", "BK-1\\nforged")).andExpect(status().isBadRequest());
    pay(VALID.replace("k-1", "link:lnk_x:tok")).andExpect(status().isBadRequest());
    pay(VALID.replace(TOKEN, "not-a-token")).andExpect(status().isBadRequest());
    pay(VALID.replace("USD", "usd")).andExpect(status().isBadRequest());
    pay(VALID.replace("k-1", "bad key with spaces")).andExpect(status().isBadRequest());
    pay("not json").andExpect(status().isBadRequest());
    verifyNoInteractions(payments);
  }

  @Test
  void aValidationErrorNamesTheFieldButNeverEchoesTheSubmittedValue() throws Exception {
    String body = pay(VALID.replace(TOKEN, "4111111111111111").replace("\"USD\"", "\"4111111111111111\""))
        .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("currency").doesNotContain("4111111111111111");
  }

  @Test
  void domainErrorsMapToTheirHttpStatus() throws Exception {
    doThrow(new ConflictException("Idempotency key was already used with different parameters")).when(payments).pay(any());
    pay(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("different parameters")));

    doThrow(new NotFoundException("Unknown payment link")).when(payments).pay(any());
    pay(VALID).andExpect(status().isNotFound());

    doThrow(new GoneException("This payment link is CANCELLED")).when(payments).pay(any());
    pay(VALID).andExpect(status().isGone());
  }

  @Test
  void anUnexpectedFailureReturnsAGenericMessageAndNoInternals() throws Exception {
    doThrow(new IllegalStateException("jdbc:postgresql://secret-host/folio password=hunter2")).when(payments).pay(any());
    String body = pay(VALID).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("Something went wrong").doesNotContain("secret-host").doesNotContain("hunter2").doesNotContain("IllegalState");
  }

  @Test
  void responsesCarryHardeningHeadersAndAreNeverCached() throws Exception {
    pay(VALID)
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().exists("Content-Security-Policy"));
  }
}
