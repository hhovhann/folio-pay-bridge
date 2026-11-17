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
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.PostingService;
import dev.foliopay.service.support.IdempotentStore;
import io.github.resilience4j.retry.Retry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class PostingServiceImpl implements PostingService {
  private static final Logger log = LoggerFactory.getLogger(PostingServiceImpl.class);
  private final PostingRepository repository;
  private final OperaClient opera;
  private final OperaProperties settings;
  private final IdempotentStore store;
  private final Retry folioRetry;

  PostingServiceImpl(PostingRepository repository, OperaClient opera, OperaProperties settings, IdempotentStore store, @Qualifier("folioRetry") Retry folioRetry) {
    this.repository = repository;
    this.opera = opera;
    this.settings = settings;
    this.store = store;
    this.folioRetry = folioRetry;
  }

  @Override
  public Posting post(String chargeId, String folioId, Money amount, LocalDate businessDate, String pspRef) {
    String key = "post:" + chargeId;
    Posting posting =
        store.findOrCreate(
            () -> repository.findByIdempotencyKey(key),
            () -> repository.saveAndFlush(new Posting(key, chargeId, folioId, amount, businessDate, pspRef)));
    return resume(posting);
  }

  @Override
  public Posting replay(String postingId) {
    return resume(repository.findById(postingId).orElseThrow(() -> new NotFoundException("Unknown posting")));
  }

  @Override
  public List<Posting> findAll() {
    return repository.findAll(org.springframework.data.domain.PageRequest.of(0, 500, org.springframework.data.domain.Sort.by("createdAt").descending())).getContent();
  }

  /** A posted entry is done, and a rejected one is not retried: OPERA said no, and asking again cannot change that. */
  private Posting resume(Posting posting) {
    return posting.isPosted() || posting.isRejected() ? posting : attempt(posting);
  }

  private Posting attempt(Posting posting) {
    posting.attempt();
    try {
      // OPERA does not promise to deduplicate: after any earlier try, look for our payment before posting again.
      Optional<OperaClient.Posted> already = posting.getAttempts() > 1 ? opera.find(settings.hotelId(), posting.getIdempotencyKey()) : Optional.empty();
      String operaRef = already.map(OperaClient.Posted::operaRef).orElseGet(() -> postWithRetry(posting));
      posting.posted(operaRef);
      log.info("Posting {} posted to folio {} as {}", posting.getId(), posting.getFolioId(), operaRef);
    } catch (OperaRejectedException e) {
      posting.deadLetter(FailureCategory.REJECTED_BY_OPERA, e.getMessage());
      log.warn("Posting {} rejected by OPERA: {}", posting.getId(), posting.getLastError());
    } catch (OperaUnavailableException e) {
      posting.deadLetter(FailureCategory.OPERA_UNAVAILABLE, e.getMessage());
      log.warn("Posting {} dead-lettered after retries: {}", posting.getId(), posting.getLastError());
    }
    return store.saveMerging(
        posting,
        repository::save,
        () -> repository.findById(posting.getId()).orElseThrow(),
        Posting::isBehind,
        stored -> {
          stored.adopt(posting);
          return stored;
        });
  }

  /** Each retry of a lost response looks first, so a post that did arrive is found, not repeated. */
  private String postWithRetry(Posting posting) {
    OperaPayment payment =
        new OperaPayment(
            settings.hotelId(), posting.getFolioId(), settings.folioWindow(), settings.paymentCode(), settings.cashierId(),
            posting.amount(), posting.getBusinessDate(), posting.getIdempotencyKey(), posting.getPspRef());
    return folioRetry.executeSupplier(
        () -> opera.find(settings.hotelId(), payment.externalRef()).map(OperaClient.Posted::operaRef).orElseGet(() -> opera.postPayment(payment)));
  }
}
