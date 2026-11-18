package dev.foliopaysim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * The outside world for the full-flow demo: an OPERA-style PMS and a card processor, as real HTTP services. Same jar as the bridge, a
 * different main class, and a different base package (dev.foliopaysim) so the bridge never scans it. Pick the actor with a profile:
 * {@code opera} or {@code psp}. Synthetic: nothing here talks to a real system, and card numbers are never stored or logged.
 */
@SpringBootApplication(
    exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class, FlywayAutoConfiguration.class}) // a test double: it checks its own fake credentials
public class SimulatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(SimulatorApplication.class, args);
  }
}
