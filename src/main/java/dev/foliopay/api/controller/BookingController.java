package dev.foliopay.api.controller;

import dev.foliopay.api.dto.BookingEventRequest;
import dev.foliopay.api.dto.BookingOutcomeResponse;
import dev.foliopay.api.dto.LinkPayRequest;
import dev.foliopay.api.dto.LinkView;
import dev.foliopay.api.dto.PaymentResponse;
import dev.foliopay.service.BookingEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import dev.foliopay.service.BusinessDateService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment links", description = "Booking events become payment requests under the written policy; the guest pays a link.")
@RestController
@RequestMapping("/api")
class BookingController {
  private final BookingEventService bookings;
  private final Clock clock;
  private final BusinessDateService businessDates;
  private final boolean demoControls;

  BookingController(BookingEventService bookings, Clock clock, BusinessDateService businessDates, @Value("${foliopay.demo-controls:false}") boolean demoControls) {
    this.bookings = bookings;
    this.clock = clock;
    this.businessDates = businessDates;
    this.demoControls = demoControls;
  }

  @Operation(summary = "Handle a booking event", description = "NEW and MODIFIED create or supersede the open payment request; CANCELLED closes it and flags a refund review when it was already paid. Repeating an event changes nothing.")
  @PostMapping("/booking-events")
  BookingOutcomeResponse event(@Valid @RequestBody BookingEventRequest request) {
    // The policy's "today" is the hotel's day. A client may set it only in the demo, so a caller cannot move the deposit dates.
    LocalDate today = demoControls && request.today() != null ? request.today() : LocalDate.now(clock);
    return BookingOutcomeResponse.from(bookings.handle(request.toEvent(), today));
  }

  @Operation(summary = "List payment links")
  @GetMapping("/links")
  List<LinkView> links() { return bookings.findAll().stream().map(LinkView::from).toList(); }

  @Operation(summary = "Pay a link", description = "Charges the link amount. The idempotency key is per link and card: a double-click is one charge; a different card after a decline is a new attempt. 410 when the link is cancelled or superseded.")
  @PostMapping("/links/{id}/pay")
  PaymentResponse pay(@PathVariable String id, @Valid @RequestBody LinkPayRequest request) {
    return PaymentResponse.from(bookings.pay(id, request.token(), businessDates.resolve(request.businessDate())));
  }
}
