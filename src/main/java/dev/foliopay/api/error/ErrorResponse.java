package dev.foliopay.api.error;

/** The only error body a client ever sees: a short, safe message. Never a stack trace, never a submitted value. */
public record ErrorResponse(String error) {}
