package dev.foliopay.repository;

import dev.foliopay.domain.model.Posting;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PostingRepository extends JpaRepository<Posting, String> {
  Optional<Posting> findByIdempotencyKey(String idempotencyKey);

  List<Posting> findByStatus(Posting.Status status);

  long countByStatus(Posting.Status status);

  long countByStatusAndFailureCategory(Posting.Status status, Posting.FailureCategory category);

  List<Posting> findByChargeIdInAndStatus(Collection<String> chargeIds, Posting.Status status);

  /**
   * Atomically takes the right to talk to OPERA about one posting until {@code until}. Returns 1 if this caller got it; 0 if the posting is
   * already posted, was rejected, or another request holds a lease that has not run out. A lease that ran out (a crashed request) can be taken.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Posting p set p.leaseUntil = :until, p.leaseOwner = :owner, p.version = p.version + 1 "
      + "where p.id = :id and p.status <> dev.foliopay.domain.model.Posting.Status.POSTED "
      + "and (p.failureCategory is null or p.failureCategory <> dev.foliopay.domain.model.Posting.FailureCategory.REJECTED_BY_OPERA) "
      + "and (p.leaseUntil is null or p.leaseUntil < :now)")
  int lease(@Param("id") String id, @Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);

  /**
   * Extends a lease this caller still holds, right before it posts. Returns 0 if the lease ran out or another request took it over: the
   * caller must then not post. The version is left alone: the lease is not business state, and the holder's copy must stay saveable.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Posting p set p.leaseUntil = :until where p.id = :id and p.leaseOwner = :owner and p.leaseUntil >= :now")
  int renew(@Param("id") String id, @Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);
}
