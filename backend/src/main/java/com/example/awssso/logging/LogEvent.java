package com.example.awssso.logging;

/**
 * One backend log line, flattened for the browser console. Deliberately not the raw
 * {@code ILoggingEvent}: only these four fields leave the server.
 */
public record LogEvent(String time, String level, String logger, String message) {
}
