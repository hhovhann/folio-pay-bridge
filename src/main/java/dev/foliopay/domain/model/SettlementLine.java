package dev.foliopay.domain.model;

/** One line of an acquirer settlement statement. */
public record SettlementLine(String pspRef, long amountMinor, String currency) {}
