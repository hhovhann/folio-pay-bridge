package dev.foliopay.repository;

import dev.foliopay.domain.model.CardToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardTokenRepository extends JpaRepository<CardToken, String> {}
