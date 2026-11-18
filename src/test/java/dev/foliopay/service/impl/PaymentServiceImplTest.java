package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.foliopay.Fixtures;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.ChargeService.ChargeCommand;
import dev.foliopay.service.PaymentService.Outcome;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.PostingService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PaymentServiceImplTest {
  private static final Money AMOUNT = new Money(2_500, "USD");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 17);
  private static final PaymentCommand COMMAND = PaymentCommand.bridge("k", "BK-1", "F-1", "tok_1", AMOUNT, DAY);

  private final ChargeService charges = mock(ChargeService.class);
  private final PostingService postings = mock(PostingService.class);
  private final PaymentServiceImpl service = new PaymentServiceImpl(charges, postings);

  private Charge charge(Charge.Status status) {
    Charge charge = Fixtures.charge("k", "BK-1", "tok_1", AMOUNT);
    if (status == Charge.Status.CAPTURED) charge.markCaptured("cap_1");
    if (status == Charge.Status.DECLINED) charge.markDeclined("no");
    when(charges.charge(Fixtures.command("k", "BK-1", "tok_1", AMOUNT))).thenReturn(charge);
    return charge;
  }

  @Test
  void aCapturedAndPostedPaymentIsOk() {
    Charge charge = charge(Charge.Status.CAPTURED);
    Posting posting = Fixtures.posting("post:" + charge.getId(), charge.getId(), "F-1", AMOUNT, DAY);
    posting.markPosted("OP-1");
    when(postings.post(charge.getId(), "F-1", AMOUNT, DAY, charge.getPspRef())).thenReturn(posting);

    assertThat(service.pay(COMMAND).outcome()).isEqualTo(Outcome.OK);
  }

  @Test
  void aCapturedPaymentWhoseFolioPostingIsWaitingSaysSoPlainly() {
    Charge charge = charge(Charge.Status.CAPTURED);
    Posting posting = Fixtures.posting("post:" + charge.getId(), charge.getId(), "F-1", AMOUNT, DAY);
    posting.moveToDeadLetter(Posting.FailureCategory.OPERA_UNAVAILABLE, "503");
    when(postings.post(any(), any(), any(), any(), any())).thenReturn(posting);

    var result = service.pay(COMMAND);

    assertThat(result.outcome()).isEqualTo(Outcome.CHARGED_NOT_POSTED);
    assertThat(result.charge()).isSameAs(charge);
  }

  @Test
  void aDeclineNeverReachesThePms() {
    charge(Charge.Status.DECLINED);
    assertThat(service.pay(COMMAND).outcome()).isEqualTo(Outcome.DECLINED);
    verifyNoInteractions(postings);
  }

  @Test
  void aPendingChargeIsNotPostedYet() {
    charge(Charge.Status.PENDING);
    var result = service.pay(COMMAND);
    assertThat(result.outcome()).isEqualTo(Outcome.CHARGE_PENDING);
    assertThat(result.posting()).isNull();
    verifyNoInteractions(postings);
    verify(charges).charge(Fixtures.command("k", "BK-1", "tok_1", AMOUNT));
  }
}
