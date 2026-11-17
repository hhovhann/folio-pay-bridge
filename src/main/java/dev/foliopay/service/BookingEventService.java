package dev.foliopay.service;

import dev.foliopay.domain.model.BookingEvent;
import dev.foliopay.domain.model.PaymentLink;
import java.time.LocalDate;
import java.util.List;

/** Booking automation: PMS booking events become payment requests under the written policy; paying a request charges and posts. */
public interface BookingEventService {
  record Outcome(List<PaymentLink> links, boolean refundReview, String note) {}

  Outcome handle(BookingEvent event, LocalDate today);

  PaymentService.Result pay(String linkId, String cardToken, LocalDate businessDate);

  List<PaymentLink> findAll();
}
