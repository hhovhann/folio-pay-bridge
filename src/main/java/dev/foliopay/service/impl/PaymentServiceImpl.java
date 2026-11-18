package dev.foliopay.service.impl;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.ChargeService.ChargeCommand;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class PaymentServiceImpl implements PaymentService {
  private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
  private final ChargeService charges;
  private final PostingService postings;

  PaymentServiceImpl(ChargeService charges, PostingService postings) {
    this.charges = charges;
    this.postings = postings;
  }

  @Override
  public Result pay(PaymentCommand command) {
    Charge charge = charges.charge(
        new ChargeCommand(command.clientId(), command.idempotencyKey(), command.bookingRef(), command.folioId(), command.businessDate(), command.cardToken(), command.amount()));
    return switch (charge.getStatus()) {
      case DECLINED -> new Result(Outcome.DECLINED, charge, null);
      case PENDING -> new Result(Outcome.CHARGE_PENDING, charge, null);
      case CAPTURED -> {
        Posting posting = postings.post(charge.getId(), charge.getFolioId(), charge.amount(), charge.getBusinessDate(), charge.getPspRef());
        log.debug("Payment {}: charge {} captured, posting {} is {}", command.idempotencyKey(), charge.getId(), posting.getId(), posting.getStatus());
        yield new Result(posting.isPosted() ? Outcome.OK : Outcome.CHARGED_NOT_POSTED, charge, posting);
      }
    };
  }
}
