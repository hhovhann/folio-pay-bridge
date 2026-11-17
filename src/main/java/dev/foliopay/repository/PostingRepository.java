package dev.foliopay.repository;

import dev.foliopay.domain.model.Posting;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, String> {
  Optional<Posting> findByIdempotencyKey(String idempotencyKey);

  List<Posting> findByStatus(Posting.Status status);
}
