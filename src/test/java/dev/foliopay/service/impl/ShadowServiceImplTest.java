package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.Fixtures;
import dev.foliopay.config.OperaProperties;
import dev.foliopay.domain.model.CardToken;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.domain.model.ShadowConfig;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.service.CardVault;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Shadow mode: the bridge compared with the legacy rules")
class ShadowServiceImplTest {
  private final PaymentService payments = mock(PaymentService.class);
  private final CardVault vault = mock(CardVault.class);
  private final OperaClient opera = mock(OperaClient.class);
  private final ShadowServiceImpl service =
      new ShadowServiceImpl(payments, vault, opera, new OperaProperties("H1", 1, "CC", 9001, Duration.ofHours(24)));

  private void tokeniseReturnsAToken() {
    when(vault.tokenise(any(Pan.class), anyString())).thenReturn(new CardToken("tok_1", new byte[1], new byte[1], "k", new Pan("4111111111111111"), "12/29", Instant.EPOCH));
  }

  private Posting posted(String key) {
    Posting posting = Fixtures.posting(key, "c", "F", new Money(1, "USD"), Fixtures.DAY);
    posting.markPosted("OP-1");
    return posting;
  }

  @Test
  @DisplayName("The same seed generates the same bookings, each with a valid shape")
  void theSameSeedGeneratesTheSameBookingsAndEachHasAValidShape() {
    var first = service.generate(7, 10);
    assertThat(first).isEqualTo(service.generate(7, 10)).hasSize(10);
    assertThat(first).allSatisfy(b -> {
      assertThat(b.nights()).isBetween(1, 5);
      assertThat(b.currency()).isEqualTo("USD");
    });
    assertThat(service.generate(8, 10)).isNotEqualTo(first);
  }

  @Test
  @DisplayName("A payment that does not end posted is counted as an error, not a match")
  void aPaymentThatDoesNotEndPostedIsCountedAsAnErrorNotAMatch() {
    tokeniseReturnsAToken();
    when(payments.pay(any())).thenReturn(new PaymentService.Result(Outcome.DECLINED, Fixtures.charge("k", "b", "t", new Money(1, "USD")), null));

    var report = service.run(1, 3, ShadowConfig.strictNew());

    assertThat(report.errors()).isEqualTo(3);
    assertThat(report.matched()).isZero();
    assertThat(report.mismatched()).isZero();
  }

  @Test
  @DisplayName("A posting OPERA cannot find is a mismatch on both fields, and the samples are bounded")
  void aPostingOperaCannotFindIsAMismatchOnBothFieldsAndTheSamplesAreBounded() {
    tokeniseReturnsAToken();
    when(payments.pay(any())).thenAnswer(call -> new PaymentService.Result(Outcome.OK, Fixtures.charge("k", "b", "t", new Money(1, "USD")), posted("post:x")));
    when(opera.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

    var report = service.run(1, 10, ShadowConfig.legacyCompatible());

    assertThat(report.mismatched()).isEqualTo(10);
    assertThat(report.byField()).containsEntry("amount", 10).containsEntry("businessDate", 10);
    assertThat(report.samples()).hasSize(12).allSatisfy(m -> assertThat(m.candidate()).isEqualTo("none"));
  }
}
