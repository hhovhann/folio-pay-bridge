package dev.foliopay.config;

import dev.foliopay.logging.AuditLog;
import dev.foliopay.logging.AuditLog.Event;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The audit trail's own lifecycle (PCI DSS v4.0.1 10.2.1.5 to 10.2.1.7, D-43). The application does not start with the trail switched off;
 * the start and the stop of the trail are themselves events; and every start records the payment and authentication configuration in
 * force. Configuration comes from the environment and cannot change while running, so a change to it is a deploy, and shows in the trail
 * as a new {@code CONFIGURATION_LOADED} with different values. Secrets are never listed: only whether one is set.
 */
@Component
class AuditTrail {
  /** Settings that decide where money goes or who may move it, recorded by value. */
  private static final List<String> RECORDED = List.of(
      "foliopay.keys", "foliopay.kms.key-id", "foliopay.gateway", "foliopay.opera.hotel-id", "foliopay.opera.payment-method",
      "foliopay.opera.cashier-id", "foliopay.opera.folio-window", "foliopay.auth.jwt.issuer", "foliopay.auth.jwt.audience",
      "foliopay.auth.jwt.scope-prefix", "foliopay.auth.disabled", "foliopay.demo-controls", "foliopay.allow-ephemeral-keys",
      "foliopay.allow-plaintext-transport", "foliopay.vault.retention", "foliopay.retry.max-attempts");
  /** Addresses, recorded without anything a URL could carry besides its scheme, host and port. */
  private static final List<String> ADDRESSES = List.of(
      "foliopay.http.opera-base-url", "foliopay.http.psp-base-url", "foliopay.webhook.url", "foliopay.auth.jwt.jwk-set-uri");
  /** Secrets, recorded only as set or not set. */
  private static final List<String> SECRETS = List.of(
      "foliopay.auth.api-keys", "foliopay.http.opera-client-secret", "foliopay.http.psp-api-key", "foliopay.webhook.secret");
  private final Environment environment;

  AuditTrail(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  void refuseToRunWithoutTheTrail() {
    if (!AuditLog.isEnabled()) {
      throw new IllegalStateException("The audit trail (logger " + AuditLog.LOGGER + ") is switched off; PCI DSS 10.2 needs it at INFO."
          + " Remove the setting that lowers its level.");
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  void started() {
    AuditLog.event(Event.AUDIT_STARTED).resource("audit-trail").success();
    AuditLog.Entry configuration = AuditLog.event(Event.CONFIGURATION_LOADED).resource("configuration");
    RECORDED.forEach(name -> configuration.detail(name, environment.getProperty(name, "")));
    ADDRESSES.forEach(name -> configuration.detail(name, origin(environment.getProperty(name, ""))));
    SECRETS.forEach(name -> configuration.detail(name, environment.getProperty(name, "").isBlank() ? "not-set" : "set"));
    configuration.success();
  }

  @EventListener(ContextClosedEvent.class)
  void stopped() {
    AuditLog.event(Event.AUDIT_STOPPED).resource("audit-trail").success();
  }

  /** Scheme, host and port only: a URL can carry a user and password, a path with a key, or a query string. */
  static String origin(String url) {
    if (url.isBlank()) {
      return "";
    }
    try {
      URI uri = URI.create(url.strip());
      return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    } catch (IllegalArgumentException unreadable) {
      return "unreadable";
    }
  }
}
