package dev.foliopaysim;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SimConfig {
  /** The simulated hotel's clock: Dubai, so the business date can sit a day behind the wall clock between midnight and night audit. */
  @Bean
  Clock clock(@Value("${sim.zone:Asia/Dubai}") ZoneId zone) {
    return Clock.system(zone);
  }
}
