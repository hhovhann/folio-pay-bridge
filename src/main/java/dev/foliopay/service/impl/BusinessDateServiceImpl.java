package dev.foliopay.service.impl;

import dev.foliopay.config.OperaProperties;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.service.BusinessDateService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The hotel's business date comes from the PMS. While the PMS is unreachable the last date it gave is used for up to a day, so a captured
 * card payment is still taken and its posting waits in the dead-letter queue instead of the guest's payment failing. If night audit has run
 * in the meantime OPERA will refuse that posting for its date: it is dead-lettered as a rejection (a known gap: no reject-and-repair yet).
 *
 * <ul>
 *   <li>No lock is held while the PMS is asked, so a slow PMS slows each payment on its own instead of queueing them all.
 *   <li>A reply is stale only if the reply to a later request already arrived: a slow answer from before night audit cannot undo the
 *       audit. The PMS's latest answer always wins otherwise, also a date the hotel corrected backwards.
 *   <li>The PMS is asked for every new charge, also right after an outage: the first answer once it is back is used at once. Skipping
 *       calls for a while after a failure was tried and removed (D-33): it kept yesterday's date after a night audit that ran during the
 *       outage, and OPERA rejects those postings for good.
 * </ul>
 */
@Service
class BusinessDateServiceImpl implements BusinessDateService {
  private static final Logger log = LoggerFactory.getLogger(BusinessDateServiceImpl.class);
  private static final Duration MAX_STALENESS = Duration.ofHours(24);
  private final OperaClient opera;
  private final OperaProperties settings;
  private final Clock clock;
  private final AtomicReference<Known> lastKnown = new AtomicReference<>();

  private final AtomicLong requests = new AtomicLong();

  /** {@code request} numbers the PMS calls in the order they were sent. */
  private record Known(LocalDate date, Instant at, long request) {
    /** The answer to the later request wins, whatever its date: an answer that arrives after a later one is stale. */
    static Known newer(Known held, Known arrived) {
      return held == null || arrived.request() > held.request() ? arrived : held;
    }
  }

  BusinessDateServiceImpl(OperaClient opera, OperaProperties settings, Clock clock) {
    this.opera = opera;
    this.settings = settings;
    this.clock = clock;
  }

  @Override
  public LocalDate resolve(LocalDate requested) {
    LocalDate pmsDate = current();
    if (requested != null && !requested.equals(pmsDate)) {
      throw new InvalidRequestException("Business date must be the hotel's current business date, " + pmsDate);
    }
    return pmsDate;
  }

  private LocalDate current() {
    Instant now = clock.instant();
    Known known = lastKnown.get();
    long request = requests.incrementAndGet();
    try {
      LocalDate date = opera.businessDate(settings.hotelId());
      return lastKnown.accumulateAndGet(new Known(date, now, request), Known::newer).date();
    } catch (OperaUnavailableException down) {
      if (usable(known, now)) {
        log.warn("PMS unreachable: using the last known business date {}", known.date());
        return known.date();
      }
      throw down;
    }
  }

  private static boolean usable(Known known, Instant now) {
    return known != null && Duration.between(known.at(), now).compareTo(MAX_STALENESS) < 0;
  }
}
