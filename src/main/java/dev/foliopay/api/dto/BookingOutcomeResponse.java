package dev.foliopay.api.dto;

import dev.foliopay.service.BookingEventService;
import java.util.List;

public record BookingOutcomeResponse(List<LinkView> links, boolean refundReview, String note) {
  public static BookingOutcomeResponse from(BookingEventService.Outcome o) {
    return new BookingOutcomeResponse(o.links().stream().map(LinkView::from).toList(), o.refundReview(), o.note());
  }
}
