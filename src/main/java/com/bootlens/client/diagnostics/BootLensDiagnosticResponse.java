package com.bootlens.client.diagnostics;

import java.time.Instant;
import java.util.Map;

public record BootLensDiagnosticResponse(
    String operation,
    BootLensDiagnosticStatus status,
    String output,
    long invocationDelayMs,
    boolean outputTruncated,
    Instant timestamp,
    String errorMessage,
    Map<String, Object> details
) {
}
