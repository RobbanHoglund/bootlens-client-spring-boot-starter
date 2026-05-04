package com.bootlens.client.diagnostics;

public record BootLensDiagnosticOperationDescriptor(
    String id,
    String displayName,
    String description,
    boolean sensitive,
    boolean expensive,
    boolean enabled,
    String disabledReason
) {
}
