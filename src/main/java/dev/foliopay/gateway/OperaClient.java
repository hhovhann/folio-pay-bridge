package dev.foliopay.gateway;

import dev.foliopay.domain.model.Money;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Port to the property management system (Oracle OPERA). OPERA Cloud (OHIP, REST) and OPERA 5 (on-premise) are two adapters behind this one
 * port. A card payment is posted as a payment to the reservation's folio window, not as a charge. OPERA posts it on its own business date: a
 * payment carries no date. OPERA does not promise to deduplicate, so a caller that is unsure whether a post arrived must {@link #find} it
 * before posting again.
 */
public interface OperaClient {
  /** Posts a payment and returns OPERA's transaction number. Throws {@link OperaUnavailableException} when it may or may not have arrived. */
  String postPayment(OperaPayment payment);

  /** The payment we posted to this reservation under this external reference, if OPERA has it on the folio. */
  Optional<Posted> find(String hotelId, String reservationId, String externalRef);

  /** The hotel's current business date. It advances only at night audit, so it is not the wall-clock date between midnight and the audit. */
  LocalDate businessDate(String hotelId);

  /** {@code cashierId} is the number OPERA gave the bridge's interface cashier; {@code paymentMethod} the hotel's code for the card type. */
  record OperaPayment(
      String hotelId, String reservationId, int folioWindow, String paymentMethod, long cashierId, Money amount, String externalRef, String pspRef) {}

  /** {@code businessDate} is the date OPERA posted it on, which can be later than the charge's if night audit ran in between. */
  record Posted(String operaRef, String reservationId, Money amount, LocalDate businessDate) {}
}
