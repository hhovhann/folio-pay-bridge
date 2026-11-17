package dev.foliopay.repository;

import dev.foliopay.domain.model.Charge;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargeRepository extends JpaRepository<Charge, String> {
  Optional<Charge> findByIdempotencyKey(String idempotencyKey);

  List<Charge> findByStatus(Charge.Status status);
}
