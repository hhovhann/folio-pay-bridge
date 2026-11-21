package dev.foliopay.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record LinkPayRequest(@NotBlank @Pattern(regexp = "tok_[a-f0-9]{32}") String token, LocalDate businessDate) {
  /** The card token can be charged by whoever holds it (D-41): a request logged whole must not carry it. */
  @Override
  public String toString() {
    return "LinkPayRequest[token=[redacted], businessDate=" + businessDate + "]";
  }
}
