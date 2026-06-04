package io.driftless.partnersim.web;

/**
 * Minimal error body returned by the centralized handler.
 *
 * @param status the HTTP status code
 * @param error a short machine-friendly error code
 * @param message a human-readable description
 */
public record ApiError(int status, String error, String message) {}
