package dev.foliopay.repository;

import dev.foliopay.domain.model.CardToken;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CardTokenRepository extends JpaRepository<CardToken, String> {
  /** Purged cards are gone: they cannot be charged. */
  boolean existsByTokenAndPurgedAtIsNull(String token);

  /** Renews a card's retention when it is opened. One conditional update: a purge that already won makes this return 0 (D-44). */
  @Modifying
  @Transactional
  @Query("update CardToken c set c.lastUsedAt = :now where c.token = :token and c.purgedAt is null")
  int markUsed(@Param("token") String token, @Param("now") Instant now);

  /** Cards unused since before {@code cutoff}, oldest first, without a payment still pending on them. */
  @Query("select c.token from CardToken c where c.purgedAt is null and c.lastUsedAt < :cutoff and not exists "
      + "(select 1 from Charge ch where ch.cardToken = c.token and ch.status = dev.foliopay.domain.model.Charge.Status.PENDING) "
      + "order by c.lastUsedAt")
  List<String> findExpired(@Param("cutoff") Instant cutoff, Pageable page);

  /**
   * Destroys one card's number: the ciphertext and the wrapped data key are emptied. The conditions are checked again in the same
   * statement, so a card opened or charged since it was found is kept: the database decides, not the time between two queries.
   */
  @Modifying
  @Transactional
  @Query("update CardToken c set c.sealedPan = :nothing, c.wrappedKey = :nothing, c.purgedAt = :now where c.token = :token and c.purgedAt is null"
      + " and c.lastUsedAt < :cutoff and not exists "
      + "(select 1 from Charge ch where ch.cardToken = c.token and ch.status = dev.foliopay.domain.model.Charge.Status.PENDING)")
  int purge(@Param("token") String token, @Param("cutoff") Instant cutoff, @Param("now") Instant now, @Param("nothing") byte[] nothing);
}
