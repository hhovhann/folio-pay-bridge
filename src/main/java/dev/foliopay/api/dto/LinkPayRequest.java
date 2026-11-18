package dev.foliopay.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record LinkPayRequest(@NotBlank @Pattern(regexp = "tok_[a-f0-9]{32}") String token, LocalDate businessDate) {}
