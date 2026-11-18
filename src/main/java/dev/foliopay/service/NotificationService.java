package dev.foliopay.service;

import dev.foliopay.domain.model.Notification;
import java.util.List;

/**
 * Tells the integrator (the hotel's PMS middleware, a booking engine, a guest-messaging app) what happened to its payments, by signed
 * webhook (D-35): captured, declined, posted to the folio, dead-lettered. Events are derived from stored state, delivered at least once
 * with retries, and end DELIVERED or FAILED, never dropped.
 */
public interface NotificationService {
  /** Queues an event for every payment and posting that reached a state not announced yet. Returns how many were queued. */
  int collect();

  /** Sends what is due, one instance per event at a time. Returns how many were delivered. */
  int deliverDue();

  List<Notification> findAll();

  /** Sends a FAILED event again from the start (the receiver was fixed). Anything else is returned unchanged. */
  Notification redeliver(String id);
}
