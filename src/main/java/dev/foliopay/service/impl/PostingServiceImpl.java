package dev.foliopay.service.impl;

import dev.foliopay.config.OperaProperties;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.domain.model.Posting.FailureCategory;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.gateway.OperaClient.OperaPayment;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.logging.Redacted;
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.PostingService;
import dev.foliopay.service.support.IdempotentStore;
import io.github.resilience4j.retry.Retry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
class PostingServiceImpl implements PostingService {
  private static final Logger log = LoggerFactory.getLogger(PostingServiceImpl.class);
  private final PostingRepository repository;
  private final OperaClient opera;
  private final OperaProperties settings;
  private final IdempotentStore store;
  private final Retry folioRetry;
  private final Clock clock;
  /** Derived from the timeouts and the retry policy ({@code ResilienceConfig.postingLease}), so a slower configuration cannot outrun it. */
  private final Duration lease;

  PostingServiceImpl(
      PostingRepository repository, OperaClient opera, OperaProperties settings, IdempotentStore store, @Qualifier("folioRetry") Retry folioRetry, Clock clock,
      @Qualifier("postingLease") Duration lease) {
    this.repository = repository;
    this.opera = opera;
    this.settings = settings;
    this.store = store;
    this.folioRetry = folioRetry;
    this.clock = clock;
    this.lease = lease;
  }

  @Override
  public Posting post(String chargeId, String folioId, Money amount, LocalDate businessDate, String pspRef) {
    String key = "post:" + chargeId;
    log.debug("Posting requested for charge {}: folio {} amount {} {} business date {}", chargeId, folioId, amount.minor(), amount.currency(), businessDate);
    Posting posting =
        store.findOrCreate(
            () -> repository.findByIdempotencyKey(key),
            () -> repository.saveAndFlush(new Posting(key, chargeId, folioId, amount, businessDate, pspRef)));
    return resume(posting);
  }

  @Override
  public Posting replay(String postingId) {
    log.info("Replay requested for posting {}", postingId);
    return resume(repository.findById(postingId).orElseThrow(() -> new NotFoundException("Unknown posting")));
  }

  @Override
  public List<Posting> findAll() {
    return repository.findAll(PageRequest.of(0, 500, Sort.by("createdAt").descending())).getContent();
  }

  /**
   * A posted entry is done, and a rejected one is not retried: OPERA said no, and asking again cannot change that. Otherwise the posting is
   * leased first, so two requests (a double-click, two replays) cannot both look, both find nothing, and both post: OPERA does not
   * deduplicate. The loser returns the stored state; the posting is still in progress, or waiting in the dead-letter queue. The holder
   * renews its lease right before each post, and does not post if it no longer holds it.
   */
  private Posting resume(Posting posting) {
    if (posting.isPosted() || posting.isRejected()) {
      log.debug("Posting {} is {} ({}): nothing to do", posting.getId(), posting.getStatus(), posting.getFailureCategory());
      return posting;
    }
    String owner = UUID.randomUUID().toString();
    Instant now = clock.instant();
    if (repository.lease(posting.getId(), owner, now, now.plus(lease)) == 0) {
      log.info("Posting {} is being posted by another request", posting.getId());
      return stored(posting);
    }
    log.debug("Posting {} leased until {} by {}", posting.getId(), now.plus(lease), owner);
    return attempt(stored(posting), owner);
  }

  private Posting stored(Posting posting) {
    return repository.findById(posting.getId()).orElseThrow();
  }

  private Posting attempt(Posting posting, String owner) {
    posting.recordAttempt();
    try {
      String operaRef = postWithRetry(posting, owner);
      posting.markPosted(operaRef);
      log.info("Posting {} posted to folio {} as {}", posting.getId(), posting.getFolioId(), operaRef);
    } catch (LeaseLost e) {
      log.warn("Posting {} not posted: its lease ran out and another request took it over", posting.getId());
      return stored(posting); // the new holder owns the outcome; writing ours could hide its post
    } catch (OperaRejectedException e) {
      posting.moveToDeadLetter(FailureCategory.REJECTED_BY_OPERA, e.getMessage());
      log.warn("Posting {} rejected by OPERA: {}", posting.getId(), posting.getLastError());
    } catch (OperaUnavailableException e) {
      posting.moveToDeadLetter(FailureCategory.OPERA_UNAVAILABLE, e.getMessage());
      log.warn("Posting {} dead-lettered after retries: {}", posting.getId(), posting.getLastError());
    } catch (RuntimeException e) {
      // Anything else (a reply we could not read, a bug) must not leave a captured payment PENDING with no replay button. It is
      // replayable: replay looks for the post first, so it cannot post twice.
      posting.moveToDeadLetter(FailureCategory.OPERA_UNAVAILABLE, "Unexpected error while posting: " + e.getClass().getSimpleName());
      log.error("Posting {} dead-lettered after an unexpected error", posting.getId(), Redacted.of(e));
    }
    return store.saveMerging(
        posting,
        repository::save,
        () -> stored(posting),
        Posting::isBehind,
        stored -> {
          stored.adopt(posting);
          return stored;
        });
  }

  /** Every attempt looks first, so a post that arrived but whose reply was lost (this time or on an earlier request) is found, not repeated. */
  private String postWithRetry(Posting posting, String owner) {
    OperaPayment payment =
        new OperaPayment(
            settings.hotelId(), posting.getFolioId(), settings.folioWindow(), settings.paymentMethod(), settings.cashierId(),
            posting.amount(), posting.getIdempotencyKey(), posting.getPspRef());
    return folioRetry.executeSupplier(
        () -> opera.find(settings.hotelId(), payment.reservationId(), payment.externalRef()).map(found -> {
          log.info("Posting {} was already on the folio as {}: not posted again", posting.getId(), found.operaRef());
          return found.operaRef();
        }).orElseGet(() -> {
          stillHeld(posting, owner);
          log.debug("Posting {} not found in OPERA: posting it", posting.getId());
          return opera.postPayment(payment);
        }));
  }

  private void stillHeld(Posting posting, String owner) {
    Instant now = clock.instant();
    if (repository.renew(posting.getId(), owner, now, now.plus(lease)) == 0) {
      throw new LeaseLost();
    }
  }

  /** Not retried (it is not an OPERA error) and never dead-letters anything: the request that took the lease over decides. */
  private static final class LeaseLost extends RuntimeException {
    LeaseLost() {
      super("lease lost", null, false, false);
    }
  }
}
