package dev.foliopay.repository;

import dev.foliopay.domain.model.Charge;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChargeRepository extends JpaRepository<Charge, String> {
  /** Keys are per client (D-40): the same key from another client is another charge. */
  Optional<Charge> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

  List<Charge> findByStatus(Charge.Status status);

  long countByStatus(Charge.Status status);

  /** Captured amount and count per currency since {@code since}, leaving out shadow runs: rows of (currency, sum of minor units, count). */
  @Query("select c.currency, sum(c.amountMinor), count(c) from Charge c where c.status = dev.foliopay.domain.model.Charge.Status.CAPTURED "
      + "and c.createdAt >= :since and c.idempotencyKey not like 'shadow-%' group by c.currency order by c.currency")
  List<Object[]> capturedSince(@Param("since") Instant since);

  List<Charge> findByStatusAndBusinessDateBetween(Charge.Status status, LocalDate from, LocalDate to);

  List<Charge> findByStatusAndPspRefIn(Charge.Status status, Collection<String> pspRefs);
}
