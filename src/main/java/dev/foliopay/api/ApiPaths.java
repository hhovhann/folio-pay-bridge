package dev.foliopay.api;

/**
 * Where the public API lives (D-39). The major version is in the path, the way payment APIs version theirs, so an old client and a new
 * one can be served side by side. Inside a version only additive changes are made: new endpoints, new optional request fields, new
 * response fields and new enum values (clients must ignore what they do not know). Anything else is a new version, and the old one stays
 * up, marked with {@link ApiDeprecation}, until its sunset date.
 */
public final class ApiPaths {
  /** Version 1 of the public API. */
  public static final String V1 = "/api/v1";

  /** Every version, present and future: a request under it that no rule names needs the {@code operations} scope (least access). */
  public static final String ALL_VERSIONS = "/api/**";

  private ApiPaths() {}
}
