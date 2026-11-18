package dev.foliopay.service;

import dev.foliopay.domain.model.BookingEvent;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.PaymentLink;
import java.time.LocalDate;
import java.util.List;

/** Booking automation: PMS booking events become payment requests under the written policy; paying a request charges and posts. */
public interface BookingEventService {
  record Outcome(List<PaymentLink> links, boolean refundReview, String note) {}

  /**
   * A payment request created directly by a third-party app (the Remote Creation API: F&B, events, spa, a guest-messaging system), with
   * its own amount and due date instead of a booking policy. One live request per reference (stored as {@code req:<reference>}, apart
   * from booking references): sending the same request again changes nothing, and sending different terms replaces the open one.
   */
  record PaymentRequest(String reference, String folioId, Money amount, LocalDate dueDate, String description) {}

  Outcome handle(BookingEvent event, LocalDate today);

  Outcome request(PaymentRequest request, LocalDate today);

  /** {@code businessDate} may be null (the usual case): the PMS date for a new charge, the stored date for a repeat. */
  PaymentService.Result pay(String linkId, String cardToken, LocalDate businessDate);

  List<PaymentLink> findAll();
}
