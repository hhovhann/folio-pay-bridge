package dev.foliopay.domain.model;

import java.time.Instant;
import java.time.ZoneId;

/** A synthetic booking replayed through the new rules and the legacy stand-in. */
public record ShadowBooking(String ref, int nights, long rateMinor, int taxBps, String currency, ZoneId zone, Instant at) {}
