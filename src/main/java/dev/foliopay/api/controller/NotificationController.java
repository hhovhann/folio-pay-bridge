package dev.foliopay.api.controller;

import dev.foliopay.api.ApiPaths;
import dev.foliopay.api.dto.NotificationView;
import dev.foliopay.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications", description = "Signed webhooks telling the integrator a payment was captured or declined and posted to the folio or dead-lettered.")
@RestController
@RequestMapping(ApiPaths.V1 + "/notifications")
class NotificationController {
  private final NotificationService notifications;

  NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @Operation(summary = "List webhook events", description = "Newest first, at most 500: PENDING (waiting for its next attempt), DELIVERED, or FAILED after the last attempt.")
  @GetMapping
  List<NotificationView> list() { return notifications.findAll().stream().map(NotificationView::from).toList(); }

  @Operation(summary = "Send a failed event again", description = "Starts a fresh round of attempts for a FAILED event once the receiver is fixed. Anything else is returned unchanged.")
  @PostMapping("/{id}/redeliver")
  NotificationView redeliver(@PathVariable String id) { return NotificationView.from(notifications.redeliver(id)); }
}
