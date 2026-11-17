package dev.foliopaysim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The outside world for the full-flow demo: an OPERA-style PMS and a card processor, as real HTTP services. Same jar as the bridge, a
 * different main class, and a different base package (dev.foliopaysim) so the bridge never scans it. Pick the actor with a profile:
 * {@code opera} or {@code psp}. Synthetic: nothing here talks to a real system, and card numbers are never stored or logged.
 */
@SpringBootApplication
public class SimulatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(SimulatorApplication.class, args);
  }
}
