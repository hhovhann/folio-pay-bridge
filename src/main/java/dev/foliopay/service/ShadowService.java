package dev.foliopay.service;

import dev.foliopay.domain.model.ShadowBooking;
import dev.foliopay.domain.model.ShadowConfig;
import dev.foliopay.domain.model.ShadowReport;
import java.util.List;

/** Shadow mode: replays the same synthetic bookings through the new rules and compares each with what the legacy system would have done. */
public interface ShadowService {
  List<ShadowBooking> generate(long seed, int count);

  ShadowReport run(long seed, int count, ShadowConfig config);
}
