package com.bootlens.client.diagnostics;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

@Endpoint(id = "bootlensDiagnostics")
public class BootLensDiagnosticsEndpoint {

    private final BootLensDiagnosticsProperties properties;
    private final VmDiagnostics vmDiagnostics;
    private final HeapDumpManager heapDumpManager;
    private final BootLensHealthDiagnosticsService healthDiagnosticsService;

    public BootLensDiagnosticsEndpoint(
        BootLensDiagnosticsProperties properties,
        VmDiagnostics vmDiagnostics,
        HeapDumpManager heapDumpManager,
        BootLensHealthDiagnosticsService healthDiagnosticsService
    ) {
        this.properties = properties;
        this.vmDiagnostics = vmDiagnostics;
        this.heapDumpManager = heapDumpManager;
        this.healthDiagnosticsService = healthDiagnosticsService;
    }

    public BootLensDiagnosticsEndpoint(
        BootLensDiagnosticsProperties properties,
        VmDiagnostics vmDiagnostics,
        HeapDumpManager heapDumpManager
    ) {
        this(properties, vmDiagnostics, heapDumpManager, null);
    }

    @ReadOperation
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("endpoint", "bootlensDiagnostics");
        summary.put("enabledOperationsCount", enabledOperationDescriptors().size());
        summary.put("sensitiveOperationsAllowed", properties.isAllowSensitive());
        summary.put("expensiveOperationsAllowed", properties.isAllowExpensive());
        summary.put("availableOperations", descriptors());
        return summary;
    }

    @ReadOperation
    public Object operations(@Selector String selector) {
        if ("operations".equalsIgnoreCase(selector)) {
            return descriptors();
        }
        if ("health-diagnostics".equalsIgnoreCase(selector)) {
            if (healthDiagnosticsService == null) {
                return Map.of(
                    "capturedAt", Instant.now(),
                    "status", "UNAVAILABLE",
                    "totalDurationMs", 0,
                    "groups", List.of(),
                    "contributors", List.of(),
                    "error", "Health diagnostics service is not available in this application."
                );
            }
            return healthDiagnosticsService.capture();
        }

        return Map.of(
            "endpoint", "bootlensDiagnostics",
            "message", "Unsupported read selector. Use /actuator/bootlensDiagnostics, /actuator/bootlensDiagnostics/operations, or /actuator/bootlensDiagnostics/health-diagnostics."
        );
    }

    @WriteOperation
    public BootLensDiagnosticResponse invoke(@Selector String operation) {
        Instant timestamp = Instant.now();
        BootLensDiagnosticOperation resolvedOperation = BootLensDiagnosticOperation.fromInput(operation).orElse(null);
        if (resolvedOperation == null) {
            return new BootLensDiagnosticResponse(
                operation,
                BootLensDiagnosticStatus.UNKNOWN_OPERATION,
                null,
                0L,
                false,
                timestamp,
                "Unknown BootLens diagnostic operation: " + operation,
                Map.of()
            );
        }

        if (resolvedOperation == BootLensDiagnosticOperation.HEAP_DUMP) {
            java.util.Optional<String> availabilityIssue = heapDumpManager.availabilityIssue();
            if (availabilityIssue.isPresent()) {
                return new BootLensDiagnosticResponse(
                    resolvedOperation.name(),
                    BootLensDiagnosticStatus.DISABLED,
                    null,
                    0L,
                    false,
                    timestamp,
                    availabilityIssue.get(),
                    Map.of()
                );
            }
        }

        if (resolvedOperation.isSensitive() && !properties.isAllowSensitive()) {
            return new BootLensDiagnosticResponse(
                resolvedOperation.name(),
                BootLensDiagnosticStatus.REJECTED_SENSITIVE,
                null,
                0L,
                false,
                timestamp,
                "Sensitive BootLens diagnostics are disabled by configuration.",
                Map.of()
            );
        }

        if (resolvedOperation.isExpensive() && !properties.isAllowExpensive()) {
            return new BootLensDiagnosticResponse(
                resolvedOperation.name(),
                BootLensDiagnosticStatus.REJECTED_EXPENSIVE,
                null,
                0L,
                false,
                timestamp,
                "Expensive BootLens diagnostics are disabled by configuration.",
                Map.of()
            );
        }

        long startTime = System.nanoTime();
        DiagnosticExecutionResult executionResult = vmDiagnostics.run(resolvedOperation);
        long invocationDelayMs = (System.nanoTime() - startTime) / 1_000_000L;
        String output = executionResult.output();
        boolean outputTruncated = false;

        if (output != null && output.length() > properties.getMaxOutputChars()) {
            output = output.substring(0, properties.getMaxOutputChars());
            outputTruncated = true;
        }

        return new BootLensDiagnosticResponse(
            resolvedOperation.name(),
            executionResult.status(),
            output,
            invocationDelayMs,
            outputTruncated,
            timestamp,
            executionResult.errorMessage(),
            executionResult.details()
        );
    }

    private List<BootLensDiagnosticOperationDescriptor> descriptors() {
        java.util.Optional<String> heapDumpAvailabilityIssue = heapDumpManager.availabilityIssue();
        return Arrays.stream(BootLensDiagnosticOperation.values())
            .map(operation -> toDescriptor(operation, heapDumpAvailabilityIssue))
            .toList();
    }

    private List<BootLensDiagnosticOperationDescriptor> enabledOperationDescriptors() {
        return descriptors().stream()
            .filter(BootLensDiagnosticOperationDescriptor::enabled)
            .toList();
    }

    private BootLensDiagnosticOperationDescriptor toDescriptor(
        BootLensDiagnosticOperation operation,
        java.util.Optional<String> heapDumpAvailabilityIssue
    ) {
        String disabledReason = null;
        if (operation == BootLensDiagnosticOperation.HEAP_DUMP && heapDumpAvailabilityIssue.isPresent()) {
            disabledReason = heapDumpAvailabilityIssue.get();
        } else if (operation.isSensitive() && !properties.isAllowSensitive()) {
            disabledReason = "Sensitive BootLens diagnostics are disabled by configuration.";
        } else if (operation.isExpensive() && !properties.isAllowExpensive()) {
            disabledReason = "Expensive BootLens diagnostics are disabled by configuration.";
        }

        boolean enabled = disabledReason == null;
        return new BootLensDiagnosticOperationDescriptor(
            operation.getId(),
            operation.getDisplayName(),
            operation.getDescription(),
            operation.isSensitive(),
            operation.isExpensive(),
            enabled,
            disabledReason
        );
    }
}
