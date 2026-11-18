package dev.foliopay.gateway;

import dev.foliopay.domain.model.Money;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Port to the property management system (Oracle OPERA). OPERA Cloud (OHIP, REST) and OPERA 5 (on-premise) are two adapters behind this one
 * port. A card payment is posted as a payment to the reservation's folio window, not as a charge. OPERA does not promise to deduplicate, so a
 * caller that is unsure whether a post arrived must {@link #find} it before posting again.
 */
public interface OperaClient {
  /** Posts a payment and returns OPERA's transaction reference. Throws {@link OperaUnavailableException} when it may or may not have arrived. */
  String postPayment(OperaPayment payment);

  /** The payment we posted under this external reference, if OPERA has it. */
  Optional<Posted> find(String hotelId, String externalRef);

  /** The hotel's current business date. It advances only at night audit, so it is not the wall-clock date between midnight and the audit. */
  LocalDate businessDate(String hotelId);

  record OperaPayment(
      String hotelId, String reservationId, int folioWindow, String paymentCode, String cashierId,
      Money amount, LocalDate businessDate, String externalRef, String pspRef) {}

  record Posted(String operaRef, String reservationId, Money amount, LocalDate businessDate) {}
}
