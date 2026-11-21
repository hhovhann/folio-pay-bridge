package dev.foliopay.service;

import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import java.time.LocalDate;
import java.util.List;

/** Posts a captured payment to the guest folio, exactly once per charge. A post that cannot be made is dead-lettered, never dropped. */
public interface PostingService {
  @SuppressWarnings("checkstyle:ParameterNumber")
  Posting post(String chargeId, String folioId, Money amount, LocalDate businessDate, String pspRef);

  /** Tries a dead-lettered posting again. Safe to repeat: the PMS deduplicates on the same reference. */
  Posting replay(String postingId);

  List<Posting> findAll();
}
