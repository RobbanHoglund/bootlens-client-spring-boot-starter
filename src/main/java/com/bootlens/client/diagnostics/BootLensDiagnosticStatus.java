package com.bootlens.client.diagnostics;

public enum BootLensDiagnosticStatus {
    SUCCESS,
    DISABLED,
    REJECTED_SENSITIVE,
    REJECTED_EXPENSIVE,
    /**
     * The operation was invoked too soon after the previous execution.
     * The caller should retry after the cooldown period indicated in the response.
     * Applies to expensive operations to prevent runaway load on the JVM.
     */
    RATE_LIMITED,
    ERROR,
    UNKNOWN_OPERATION
}
