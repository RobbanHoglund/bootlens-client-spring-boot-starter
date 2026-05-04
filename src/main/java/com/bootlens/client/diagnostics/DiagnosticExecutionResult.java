package com.bootlens.client.diagnostics;

import java.util.Map;

record DiagnosticExecutionResult(
    BootLensDiagnosticStatus status,
    String output,
    String errorMessage,
    Map<String, Object> details
) {

    static DiagnosticExecutionResult success(String output) {
        return new DiagnosticExecutionResult(BootLensDiagnosticStatus.SUCCESS, output, null, Map.of());
    }

    static DiagnosticExecutionResult success(String output, Map<String, Object> details) {
        return new DiagnosticExecutionResult(BootLensDiagnosticStatus.SUCCESS, output, null, details == null ? Map.of() : Map.copyOf(details));
    }

    static DiagnosticExecutionResult error(String errorMessage) {
        return new DiagnosticExecutionResult(BootLensDiagnosticStatus.ERROR, null, errorMessage, Map.of());
    }
}
