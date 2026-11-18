package dev.foliopay.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Retry policy for calls to the processor and the PMS: exponential backoff with jitter, bounded. */
@ConfigurationProperties("foliopay.retry")
public record RetryProperties(
    @DefaultValue("3") int maxAttempts,
    @DefaultValue("200ms") Duration initialBackoff,
    @DefaultValue("2.0") double multiplier,
    @DefaultValue("0.5") double jitter) {}
