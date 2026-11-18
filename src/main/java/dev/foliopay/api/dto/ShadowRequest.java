package dev.foliopay.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record ShadowRequest(@Min(1) @Max(200) Integer bookings, Long seed, @Pattern(regexp = "compat|strict") String mode) {}
