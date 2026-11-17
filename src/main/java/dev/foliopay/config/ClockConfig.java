package dev.foliopay.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One clock for "today", in the hotel business zone (not the JVM zone, which is UTC in a container). Injectable so tests can fix it. */
@Configuration
class ClockConfig {
  @Bean
  Clock clock(@Value("${foliopay.business-zone:Asia/Dubai}") ZoneId zone) {
    return Clock.system(zone);
  }
}
