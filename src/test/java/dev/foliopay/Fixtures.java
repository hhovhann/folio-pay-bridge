package dev.foliopay;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.service.ChargeService.ChargeCommand;
import java.time.LocalDate;

/** Small builders so a test says only what it cares about. */
public final class Fixtures {
  public static final LocalDate DAY = LocalDate.of(2026, 11, 17);

  private Fixtures() {}

  public static Charge charge(String key, String bookingRef, String token, Money amount) {
    return new Charge(key, bookingRef, "F-1", DAY, token, amount);
  }

  public static ChargeCommand command(String key, String bookingRef, String token, Money amount) {
    return new ChargeCommand(key, bookingRef, "F-1", DAY, token, amount);
  }

  public static Posting posting(String key, String chargeId, String folioId, Money amount, LocalDate day) {
    return new Posting(key, chargeId, folioId, amount, day, "cap_1");
  }
}
