package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

class BootLensDiagnosticsEndpointTest {

    @Test
    void unknownOperationReturnsUnknownStatus() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        BootLensDiagnosticResponse response = endpoint.invoke("does-not-exist");

        assertThat(response.status()).isEqualTo(BootLensDiagnosticStatus.UNKNOWN_OPERATION);
    }

    @Test
    void sensitiveOperationIsRejectedWhenDisabled() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        properties.getHeapDump().setEnabled(true);
        properties.setAllowSensitive(false);
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        BootLensDiagnosticResponse response = endpoint.invoke("HEAP_DUMP");

        assertThat(response.status()).isEqualTo(BootLensDiagnosticStatus.REJECTED_SENSITIVE);
    }

    @Test
    void expensiveOperationIsRejectedWhenDisabled() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        properties.getHeapDump().setEnabled(true);
        properties.setAllowSensitive(true);
        properties.setAllowExpensive(false);
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        BootLensDiagnosticResponse response = endpoint.invoke("HEAP_DUMP");

        assertThat(response.status()).isEqualTo(BootLensDiagnosticStatus.REJECTED_EXPENSIVE);
    }

    @Test
    void outputIsTruncatedWhenLongerThanMax() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        properties.setAllowSensitive(true);
        properties.setMaxOutputChars(10);
        VmDiagnostics diagnostics = new VmDiagnostics(
            (MBeanServer) null,
            new ObjectName("com.sun.management:type=DiagnosticCommand"),
            new SecretSanitizer(properties),
            properties
        ) {
            @Override
            DiagnosticExecutionResult run(BootLensDiagnosticOperation operation) {
                return DiagnosticExecutionResult.success("01234567890123456789");
            }
        };

        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(properties, diagnostics, new HeapDumpManager(properties));
        BootLensDiagnosticResponse response = endpoint.invoke("ENV");

        assertThat(response.status()).isEqualTo(BootLensDiagnosticStatus.SUCCESS);
        assertThat(response.output()).isEqualTo("0123456789");
        assertThat(response.outputTruncated()).isTrue();
    }

    @Test
    void responsePreservesExecutionDetails() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        VmDiagnostics diagnostics = new VmDiagnostics(
            (MBeanServer) null,
            new ObjectName("com.sun.management:type=DiagnosticCommand"),
            new SecretSanitizer(properties),
            properties
        ) {
            @Override
            DiagnosticExecutionResult run(BootLensDiagnosticOperation operation) {
                return DiagnosticExecutionResult.success("ok", java.util.Map.of("threadDumpSource", "diagnostic-command"));
            }
        };

        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(properties, diagnostics, new HeapDumpManager(properties));
        BootLensDiagnosticResponse response = endpoint.invoke("THREAD_DUMP");

        assertThat(response.status()).isEqualTo(BootLensDiagnosticStatus.SUCCESS);
        assertThat(response.details()).containsEntry("threadDumpSource", "diagnostic-command");
    }

    @Test
    void expensiveOperationIsRateLimitedOnSecondCallWithinCooldown() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        properties.setAllowExpensive(true);
        properties.setExpensiveOperationCooldown(java.time.Duration.ofSeconds(60));
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(
            properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        // First call should succeed
        BootLensDiagnosticResponse first = endpoint.invoke("THREAD_DUMP");
        assertThat(first.status()).isEqualTo(BootLensDiagnosticStatus.SUCCESS);

        // Immediate second call should be rate-limited
        BootLensDiagnosticResponse second = endpoint.invoke("THREAD_DUMP");
        assertThat(second.status()).isEqualTo(BootLensDiagnosticStatus.RATE_LIMITED);
        assertThat(second.errorMessage()).contains("executed recently");
        assertThat(second.details()).containsKey("cooldownSeconds");
        assertThat(second.details()).containsKey("remainingSeconds");
    }

    @Test
    void expensiveOperationIsNotRateLimitedWhenCooldownIsZero() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        properties.setAllowExpensive(true);
        properties.setExpensiveOperationCooldown(java.time.Duration.ZERO);
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(
            properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        endpoint.invoke("THREAD_DUMP");
        BootLensDiagnosticResponse second = endpoint.invoke("THREAD_DUMP");
        assertThat(second.status()).isEqualTo(BootLensDiagnosticStatus.SUCCESS);
    }

    @Test
    void nonExpensiveOperationIsNeverRateLimited() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        properties.setAllowExpensive(true);
        properties.setExpensiveOperationCooldown(java.time.Duration.ofSeconds(60));
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(
            properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        // VM_FLAGS is not expensive — should never be rate-limited
        endpoint.invoke("VM_FLAGS");
        BootLensDiagnosticResponse second = endpoint.invoke("VM_FLAGS");
        // sensitive by default, so REJECTED_SENSITIVE — but definitely not RATE_LIMITED
        assertThat(second.status()).isNotEqualTo(BootLensDiagnosticStatus.RATE_LIMITED);
    }

    @Test
    void heapDumpIsDisabledByDefault() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        BootLensDiagnosticResponse response = endpoint.invoke("HEAP_DUMP");

        assertThat(response.status()).isEqualTo(BootLensDiagnosticStatus.DISABLED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void heapDumpExistsInOperationDescriptorsByDefaultAndIsDisabledWithReason() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        List<BootLensDiagnosticOperationDescriptor> descriptors =
            (List<BootLensDiagnosticOperationDescriptor>) endpoint.operations("operations");

        BootLensDiagnosticOperationDescriptor heapDumpDescriptor = descriptors.stream()
            .filter(descriptor -> "heap-dump".equals(descriptor.id()))
            .findFirst()
            .orElseThrow();

        assertThat(heapDumpDescriptor.enabled()).isFalse();
        assertThat(heapDumpDescriptor.disabledReason()).isEqualTo("Heap dump is disabled by client configuration.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthDiagnosticsSelectorReturnsUnavailablePayloadWhenServiceIsMissing() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        BootLensDiagnosticsEndpoint endpoint = new BootLensDiagnosticsEndpoint(properties, stubVmDiagnostics(), new HeapDumpManager(properties));

        Object response = endpoint.operations("health-diagnostics");

        assertThat(response).isInstanceOf(Map.class);
        Map<String, Object> payload = (Map<String, Object>) response;
        assertThat(payload).containsEntry("status", "UNAVAILABLE");
        assertThat(payload).containsKey("contributors");
        assertThat(payload).containsKey("groups");
    }

    private static BootLensDiagnosticsProperties defaultProperties() {
        BootLensDiagnosticsProperties properties = new BootLensDiagnosticsProperties();
        return properties;
    }

    private static VmDiagnostics stubVmDiagnostics() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        return new VmDiagnostics(
            (MBeanServer) null,
            new ObjectName("com.sun.management:type=DiagnosticCommand"),
            new SecretSanitizer(properties),
            properties
        ) {
            @Override
            DiagnosticExecutionResult run(BootLensDiagnosticOperation operation) {
                return DiagnosticExecutionResult.success("ok");
            }
        };
    }
}
