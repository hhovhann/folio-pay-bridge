package dev.foliopay.service.impl;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Notification;
import dev.foliopay.domain.model.PaymentLink;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.repository.NotificationRepository;
import dev.foliopay.repository.PaymentLinkRepository;
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.OverviewService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class OverviewServiceImpl implements OverviewService {
  private static final Logger log = LoggerFactory.getLogger(OverviewServiceImpl.class);
  private final ChargeRepository charges;
  private final PostingRepository postings;
  private final NotificationRepository notifications;
  private final PaymentLinkRepository links;
  private final Clock clock;

  OverviewServiceImpl(ChargeRepository charges, PostingRepository postings, NotificationRepository notifications, PaymentLinkRepository links,
      Clock clock) {
    this.charges = charges;
    this.postings = postings;
    this.notifications = notifications;
    this.links = links;
    this.clock = clock;
  }

  /** "Today" starts at midnight in the hotel's zone (the clock's zone, D-18), not at midnight UTC. */
  @Override
  public Overview overview() {
    log.debug("overview()");
    var startOfDay = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
    List<Captured> captured = charges.capturedSince(startOfDay).stream()
        .map(row -> new Captured((String) row[0], ((Number) row[1]).longValue(), ((Number) row[2]).longValue())).toList();
    return new Overview(
        clock.instant(),
        captured,
        charges.countByStatus(Charge.Status.PENDING),
        postings.countByStatus(Posting.Status.PENDING),
        postings.countByStatusAndFailureCategory(Posting.Status.DEAD_LETTER, Posting.FailureCategory.OPERA_UNAVAILABLE),
        postings.countByStatusAndFailureCategory(Posting.Status.DEAD_LETTER, Posting.FailureCategory.REJECTED_BY_OPERA),
        notifications.countByStatus(Notification.Status.PENDING),
        notifications.countByStatus(Notification.Status.FAILED),
        links.countByStatus(PaymentLink.Status.OPEN),
        links.countByStatus(PaymentLink.Status.PAYING));
  }
}
