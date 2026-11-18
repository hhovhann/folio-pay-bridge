package dev.foliopay.api.dto;

import dev.foliopay.service.PaymentService;

public record PaymentResponse(PaymentService.Outcome outcome, ChargeView charge, PostingView posting) {
  public static PaymentResponse from(PaymentService.Result r) {
    return new PaymentResponse(r.outcome(), ChargeView.from(r.charge()), PostingView.from(r.posting()));
  }
}
