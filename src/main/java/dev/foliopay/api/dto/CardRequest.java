package dev.foliopay.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CardRequest(
    @NotBlank @Size(max = 23) String pan, @NotBlank @Pattern(regexp = "(0[1-9]|1[0-2])/\\d{2}") String expiry) {
  /** A card number must never reach a log line, even through this request object. */
  @Override
  public String toString() {
    return "CardRequest[redacted]";
  }
}
