package dev.foliopay.api.dto;

import dev.foliopay.domain.model.Posting;
import java.time.Instant;
import java.time.LocalDate;

public record PostingView(String id, String chargeId, String folioId, long amountMinor, String currency, LocalDate businessDate, Posting.Status status, String operaRef, Posting.FailureCategory failureCategory, String lastError, int attempts, Instant createdAt) {
  public static PostingView from(Posting p) {
    return p == null ? null : new PostingView(p.getId(), p.getChargeId(), p.getFolioId(), p.getAmountMinor(), p.getCurrency(), p.getBusinessDate(), p.getStatus(), p.getOperaRef(), p.getFailureCategory(), p.getLastError(), p.getAttempts(), p.getCreatedAt());
  }
}
