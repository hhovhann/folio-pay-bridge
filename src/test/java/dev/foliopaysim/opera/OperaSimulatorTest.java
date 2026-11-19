package dev.foliopaysim.opera;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopaysim.opera.OperaSimulator.Reservation;
import dev.foliopaysim.opera.OperaSimulator.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The front desk's own rules, on the simulator itself: a reservation is created once, and a room is never given to two guests. */
@DisplayName("OPERA simulator: reservations and rooms at the front desk")
class OperaSimulatorTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 19);
  private final OperaSimulator pms =
      new OperaSimulator(Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC), 3600, "app", "client", "secret", "ENT");

  private List<String> roomsInHouse() {
    return List.of("RES-1001", "RES-A", "RES-B", "RES-C").stream().map(id -> pms.reservation(id).orElseThrow())
        .filter(r -> r.status() == Status.IN_HOUSE).map(Reservation::room).toList();
  }

  /**
   * Fails if a room is numbered by how many guests are in house: after a guest leaves, that count points at a room still taken, and two
   * guests shared it (OHIP review). The seeded guest RES-1001 is in house in 301.
   */
  @Test
  @DisplayName("A guest checking in gets a room no guest in house holds, even after another guest has left")
  void aGuestCheckingInGetsARoomNoGuestInHouseHoldsEvenAfterAnotherGuestHasLeft() {
    for (String id : List.of("RES-A", "RES-B", "RES-C")) {
      pms.book(id, "Synthetic " + id, DAY, 1, 10_000, "USD");
    }
    pms.checkIn("RES-A");
    pms.checkIn("RES-B");
    assertThat(pms.checkOut("RES-A")).as("nothing on the folio, so it is settled").isEmpty();
    pms.checkIn("RES-C");

    assertThat(roomsInHouse()).as("RES-1001, RES-B and RES-C").hasSize(3).doesNotHaveDuplicates();
    assertThat(pms.reservation("RES-C").orElseThrow().room()).as("the room RES-A gave back").isEqualTo("302");
  }

  /** Fails if booking an id the PMS already holds replaces the stay: a repeated booking event put a guest in house back to due-in, roomless. */
  @Test
  @DisplayName("Booking a reservation the PMS already holds changes nothing, so a guest in house stays in house")
  void bookingAReservationThePmsAlreadyHoldsChangesNothing() {
    pms.book("RES-A", "Synthetic RES-A", DAY, 2, 20_000, "USD");
    pms.checkIn("RES-A");
    Reservation inHouse = pms.reservation("RES-A").orElseThrow();

    pms.book("RES-A", "Synthetic RES-A", DAY, 2, 20_000, "USD");

    assertThat(pms.reservation("RES-A").orElseThrow()).isEqualTo(inHouse).satisfies(r -> assertThat(r.status()).isEqualTo(Status.IN_HOUSE));
  }
}
