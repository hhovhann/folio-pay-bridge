package dev.foliopay.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint (or a whole controller) of an old API version that is kept for existing clients while new clients use its successor
 * (D-39). Every response then says so in the standard headers, so a client's own monitoring can see it before the endpoint goes away:
 * {@code Deprecation} (RFC 9745), {@code Sunset} (RFC 8594) and {@code Link: <successor>; rel="successor-version"}. Each call is also
 * logged with the calling client, so operators know who still has to move before the sunset date.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ApiDeprecation {
  /** The day it was deprecated, ISO-8601 ({@code 2027-01-31}). */
  String since();

  /** The day after which it may stop answering, ISO-8601. Announced to integrators when the endpoint is deprecated. */
  String sunset();

  /** The path of the endpoint that replaces it, for example {@code /api/v2/payments}. Empty when there is no direct replacement. */
  String successor() default "";
}
