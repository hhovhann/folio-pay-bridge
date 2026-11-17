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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The hotel's business date comes from the PMS. While the PMS is unreachable the last date it gave is used for up to a day, so a captured
 * card payment is still taken and its posting waits in the dead-letter queue instead of the guest's payment failing. If night audit has run
 * in the meantime OPERA will refuse that posting for its date: it is dead-lettered as a rejection (a known gap: no reject-and-repair yet).
 */
@Service
class BusinessDateServiceImpl implements BusinessDateService {
  private static final Logger log = LoggerFactory.getLogger(BusinessDateServiceImpl.class);
  private static final Duration MAX_STALENESS = Duration.ofHours(24);
  private final OperaClient opera;
  private final OperaProperties settings;
  private final Clock clock;
  private LocalDate lastKnown;
  private Instant lastKnownAt;

  BusinessDateServiceImpl(OperaClient opera, OperaProperties settings, Clock clock) {
    this.opera = opera;
    this.settings = settings;
    this.clock = clock;
  }

  @Override
  public synchronized LocalDate resolve(LocalDate requested) {
    LocalDate pmsDate = current();
    if (requested != null && !requested.equals(pmsDate)) {
      throw new InvalidRequestException("Business date must be the hotel's current business date, " + pmsDate);
    }
    return pmsDate;
  }

  private LocalDate current() {
    try {
      lastKnown = opera.businessDate(settings.hotelId());
      lastKnownAt = Instant.now(clock);
      return lastKnown;
    } catch (OperaUnavailableException down) {
      if (lastKnown != null && Duration.between(lastKnownAt, Instant.now(clock)).compareTo(MAX_STALENESS) < 0) {
        log.warn("PMS unreachable: using the last known business date {}", lastKnown);
        return lastKnown;
      }
      throw down;
    }
  }
}
