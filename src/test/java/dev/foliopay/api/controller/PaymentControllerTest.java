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

import dev.foliopay.Fixtures;
import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.GoneException;
import dev.foliopay.domain.exception.NotFoundException;
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
import org.junit.jupiter.api.DisplayName;
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
@Import({PaymentControllerTest.FixedClock.class, dev.foliopay.config.security.SecurityConfig.class, dev.foliopay.config.security.DashboardAuth.class})
@DisplayName("Payment API: the HTTP contract of each endpoint, with the services mocked")
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
    return mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  @DisplayName("A valid payment without a date leaves the date to the service, so a repeat after night audit can resume")
  void aValidPaymentWithoutADateLeavesTheDateToTheServiceSoARepeatAfterNightAuditCanResume() throws Exception {
    Mockito.when(businessDates.checkRequested(any())).thenCallRealMethod();
    Charge charge = Fixtures.charge("k-1", "BK-1", "tok_secret_token", new Money(12_345, "USD"));
    charge.markCaptured("cap_1");
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.CHARGE_PENDING, charge, null));

    String json = pay(VALID).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("CHARGE_PENDING"))
        .andReturn().getResponse().getContentAsString();

    ArgumentCaptor<PaymentCommand> sent = ArgumentCaptor.forClass(PaymentCommand.class);
    Mockito.verify(payments).pay(sent.capture());
    assertThat(sent.getValue().businessDate()).as("no date: the charge service decides (PMS date if new, stored date if a repeat)").isNull();
    Mockito.verify(businessDates, Mockito.never()).resolve(any());
    assertThat(sent.getValue().amount()).isEqualTo(new Money(12_345, "USD"));
    assertThat(json).as("the card token is not part of the response").doesNotContain("tok_secret_token").doesNotContain("cardToken");
  }

  @Test
  @DisplayName("A concurrent change to the same record is a conflict to retry, not a server error")
  void aConcurrentChangeToTheSameRecordIsAConflictToRetryNotAServerError() throws Exception {
    when(payments.pay(any())).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Charge.class, "c-1"));
    pay(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("send it again")));
  }

  @Test
  @DisplayName("A date the client sends is checked against the PMS")
  void aDateTheClientSendsIsCheckedAgainstThePms() throws Exception {
    Mockito.when(businessDates.checkRequested(any())).thenCallRealMethod();
    when(businessDates.resolve(LocalDate.of(2026, 11, 17))).thenReturn(LocalDate.of(2026, 11, 17));
    Charge charge = Fixtures.charge("k-1", "BK-1", "tok_1", new Money(12_345, "USD"));
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.CHARGE_PENDING, charge, null));

    pay(VALID.replace("\"currency\":\"USD\"", "\"currency\":\"USD\",\"businessDate\":\"2026-11-17\"")).andExpect(status().isOk());

    Mockito.verify(businessDates).resolve(LocalDate.of(2026, 11, 17));
    ArgumentCaptor<PaymentCommand> sent = ArgumentCaptor.forClass(PaymentCommand.class);
    Mockito.verify(payments).pay(sent.capture());
    assertThat(sent.getValue().businessDate()).isEqualTo(LocalDate.of(2026, 11, 17));
  }

  @Test
  @DisplayName("Invalid input is rejected before any service is called")
  void invalidInputIsRejectedBeforeAnyServiceIsCalled() throws Exception {
    pay("{\"idempotencyKey\":\"x\"}").andExpect(status().isBadRequest());
    pay(VALID.replace("12345", "0")).andExpect(status().isBadRequest());
    pay(VALID.replace("12345", "1000000000001")).andExpect(status().isBadRequest());
    pay(VALID.replace("BK-1", "BK-1\\nforged")).andExpect(status().isBadRequest());
    pay(VALID.replace("k-1", "link:lnk_x:tok")).andExpect(status().isBadRequest());
    pay(VALID.replace("k-1", "post:ch-1")).andExpect(status().isBadRequest());
    pay(VALID.replace("k-1", "shadow-run1-BK-1")).andExpect(status().isBadRequest());
    pay(VALID.replace(TOKEN, "not-a-token")).andExpect(status().isBadRequest());
    pay(VALID.replace("USD", "usd")).andExpect(status().isBadRequest());
    pay(VALID.replace("k-1", "bad key with spaces")).andExpect(status().isBadRequest());
    pay("not json").andExpect(status().isBadRequest());
    verifyNoInteractions(payments);
  }

  @Test
  @DisplayName("A validation error names the field but never echoes the submitted value")
  void aValidationErrorNamesTheFieldButNeverEchoesTheSubmittedValue() throws Exception {
    String body = pay(VALID.replace(TOKEN, "4111111111111111").replace("\"USD\"", "\"4111111111111111\""))
        .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("currency").doesNotContain("4111111111111111");
  }

  @Test
  @DisplayName("Domain errors map to their HTTP status")
  void domainErrorsMapToTheirHttpStatus() throws Exception {
    doThrow(new ConflictException("Idempotency key was already used with different parameters")).when(payments).pay(any());
    pay(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different parameters")));

    doThrow(new NotFoundException("Unknown payment link")).when(payments).pay(any());
    pay(VALID).andExpect(status().isNotFound());

    doThrow(new GoneException("This payment link is CANCELLED")).when(payments).pay(any());
    pay(VALID).andExpect(status().isGone());
  }

  @Test
  @DisplayName("A downstream outage is a 503 telling the client to retry with the same key")
  void aDownstreamOutageIsA503TellingTheClientToRetryWithTheSameKey() throws Exception {
    doThrow(new dev.foliopay.gateway.OperaUnavailableException("jdbc secret-host")).when(payments).pay(any());
    String body = pay(VALID).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("same idempotency key").doesNotContain("secret-host");

    doThrow(new dev.foliopay.gateway.PspTransientException("timeout")).when(payments).pay(any());
    pay(VALID).andExpect(status().isServiceUnavailable());
  }

  @Test
  @DisplayName("An unexpected failure returns a generic message and no internals")
  void anUnexpectedFailureReturnsAGenericMessageAndNoInternals() throws Exception {
    doThrow(new IllegalStateException("jdbc:postgresql://secret-host/folio password=hunter2")).when(payments).pay(any());
    String body = pay(VALID).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("Something went wrong").doesNotContain("secret-host").doesNotContain("hunter2").doesNotContain("IllegalState");
  }

  @Test
  @DisplayName("Responses carry hardening headers and are never cached")
  void responsesCarryHardeningHeadersAndAreNeverCached() throws Exception {
    pay(VALID)
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().exists("Content-Security-Policy"));
  }

  /** Fails if the two 409s look alike: a client could not tell "send it again" from "you sent a different request under an old key". */
  @Test
  @DisplayName("The two conflicts carry different codes, and only the retryable one has Retry-After")
  void theTwoConflictsCarryDifferentCodesAndOnlyTheRetryableOneHasRetryAfter() throws Exception {
    when(payments.pay(any())).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Charge.class, "c-1"));
    pay(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE")).andExpect(header().exists("Retry-After"));

    org.mockito.Mockito.reset(payments);
    when(payments.pay(any())).thenThrow(new dev.foliopay.domain.exception.IdempotencyKeyReusedException());
    pay(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED")).andExpect(header().doesNotExist("Retry-After"));
  }

  @Test
  @DisplayName("An outage is retryable and says when; a server error has a code but no detail")
  void anOutageIsRetryableAndSaysWhenAndAServerErrorHasACodeButNoDetail() throws Exception {
    doThrow(new dev.foliopay.gateway.PspTransientException("timeout")).when(payments).pay(any());
    pay(VALID).andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE")).andExpect(header().string("Retry-After", "2"));

    doThrow(new IllegalStateException("boom")).when(payments).pay(any());
    pay(VALID).andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(header().doesNotExist("Retry-After"));
  }
}

