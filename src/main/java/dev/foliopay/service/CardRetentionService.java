package dev.foliopay.service;

/** Destroys the card numbers that have outlived their retention period (PCI DSS v4.0.1 3.2.1, D-44). */
public interface CardRetentionService {
  /** Purges the cards unused for longer than the retention period, none with a payment pending; returns how many. Safe on every instance. */
  int purgeExpired();
}
