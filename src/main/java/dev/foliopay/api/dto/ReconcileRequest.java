package dev.foliopay.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * mode "clean" or "messy" reconciles against the acquirer's own statement (messy only in demo); csv reconciles a pasted statement.
 * {@code from} and {@code to} are the business dates the statement covers: only captures taken on them are expected in it. Leave both out
 * to expect every capture ever made (demos and small pilots only).
 */
public record ReconcileRequest(@Pattern(regexp = "clean|messy") String mode, @Size(max = 200_000) String csv, LocalDate from, LocalDate to) {}
