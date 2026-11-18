package dev.foliopay.api.error;

/**
 * The only error body a client ever sees: a short, safe message and a stable machine-readable code. Never a stack trace, never a
 * submitted value. The code tells a client what to do: retryable codes also carry a {@code Retry-After} header (see {@link ErrorCode}).
 */
public record ErrorResponse(String error, String code) {}
