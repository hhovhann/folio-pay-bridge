package dev.foliopay.api.controller;

import dev.foliopay.api.ApiPaths;
import dev.foliopay.api.dto.ShadowRequest;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.ShadowConfig;
import dev.foliopay.domain.model.ShadowReport;
import dev.foliopay.service.ShadowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shadow runs push synthetic payments through the whole flow, so they exist only when demo controls are on. */
@Tag(name = "Demo", description = "Present only when foliopay.demo-controls=true.")
@RestController
@RequestMapping(ApiPaths.V1 + "/shadow")
@ConditionalOnProperty(name = "foliopay.demo-controls", havingValue = "true")
class ShadowController {
  private static final long DEFAULT_SEED = 42;
  private static final int DEFAULT_BOOKINGS = 200;
  private final ObjectProvider<ShadowService> shadow;

  ShadowController(ObjectProvider<ShadowService> shadow) { this.shadow = shadow; }

  @Operation(summary = "Run shadow mode", description = "Replays synthetic bookings through the payment flow and compares each with the legacy stand-in.")
  @PostMapping
  ShadowReport run(@Valid @RequestBody ShadowRequest request) {
    ShadowConfig config = "compat".equals(request.mode()) ? ShadowConfig.legacyCompatible() : ShadowConfig.strictNew();
    ShadowService service = shadow.getIfAvailable();
    if (service == null) {
      throw new NotFoundException("Shadow runs use the in-memory fakes and are not available against external systems");
    }
    return service.run(request.seed() == null ? DEFAULT_SEED : request.seed(), request.bookings() == null ? DEFAULT_BOOKINGS : request.bookings(), config);
  }
}
