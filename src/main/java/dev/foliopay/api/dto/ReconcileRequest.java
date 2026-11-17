package dev.foliopay.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** mode "clean" or "messy" reconciles against the acquirer's own statement (messy only in demo); csv reconciles a pasted statement. */
public record ReconcileRequest(@Pattern(regexp = "clean|messy") String mode, @Size(max = 200_000) String csv) {}
