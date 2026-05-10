package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

class VmDiagnosticsTest {

    @Test
    void threadDumpUsesDiagnosticCommandThreadPrintWithLockDetailsByDefault() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        AtomicReference<String> invokedOperation = new AtomicReference<>();
        AtomicReference<String[]> invokedArguments = new AtomicReference<>();
        MBeanServer mBeanServer = diagnosticCommandServer(invokedOperation, invokedArguments, "\"demo-thread\" #42\n\tat demo.Work.run(Work.java:12)");
        VmDiagnostics diagnostics = new VmDiagnostics(
            mBeanServer,
            new ObjectName("com.sun.management:type=DiagnosticCommand"),
            new SecretSanitizer(properties),
            properties
        );

        DiagnosticExecutionResult result = diagnostics.run(BootLensDiagnosticOperation.THREAD_DUMP);

        assertThat(result.status()).isEqualTo(BootLensDiagnosticStatus.SUCCESS);
        assertThat(result.output()).contains("\"demo-thread\" #42");
        assertThat(result.details()).containsEntry("threadDumpSource", "diagnostic-command");
        assertThat(invokedOperation.get()).isEqualTo("threadPrint");
        assertThat(invokedArguments.get()).containsExactly("-l");
    }

    @Test
    void threadDumpFallsBackToThreadMxBeanWhenDiagnosticCommandIsUnavailable() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        VmDiagnostics diagnostics = new VmDiagnostics(
            unavailableDiagnosticCommandServer(),
            new ObjectName("com.sun.management:type=DiagnosticCommand"),
            new SecretSanitizer(properties),
            properties
        );

        DiagnosticExecutionResult result = diagnostics.run(BootLensDiagnosticOperation.THREAD_DUMP);

        assertThat(result.status()).isEqualTo(BootLensDiagnosticStatus.SUCCESS);
        assertThat(result.output()).isNotBlank();
        assertThat(result.details()).containsEntry("threadDumpSource", "mxbean-fallback");
    }

    @Test
    void securityReportContainsFullSections() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        properties.setSanitizePrivacy(false);
        VmDiagnostics diagnostics = createDiagnostics(properties);

        DiagnosticExecutionResult result = diagnostics.run(BootLensDiagnosticOperation.SECURITY_REPORT);

        assertThat(result.output()).contains("Supported Cipher Suites");
        assertThat(result.output()).contains("Cryptography Providers");
        assertThat(result.output()).contains("Default SSLSocketFactory");
        assertThat(result.output()).contains("Default SSLServerSocketFactory");
    }

    @Test
    void securityReportSanitizesPrivacySensitiveProperties() throws Exception {
        BootLensDiagnosticsProperties properties = defaultProperties();
        VmDiagnostics diagnostics = createDiagnostics(properties);

        withSystemProperty("user.name", "bootlens-test-user", () -> withSystemProperty("java.class.path", "app.jar;libs\\a.jar", () -> {
            DiagnosticExecutionResult result = diagnostics.run(BootLensDiagnosticOperation.SECURITY_REPORT);

            assertThat(result.output()).contains("user.name=<redacted>");
            assertThat(result.output()).doesNotContain("user.name=bootlens-test-user");
            assertThat(result.output()).contains("java.class.path=<classpath omitted>");
            assertThat(result.output()).doesNotContain("java.class.path=app.jar;libs\\a.jar");
        }));
    }

    private static VmDiagnostics createDiagnostics(BootLensDiagnosticsProperties properties) throws Exception {
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName("com.sun.management:type=DiagnosticCommand");
        return new VmDiagnostics(mBeanServer, objectName, new SecretSanitizer(properties), properties);
    }

    private static MBeanServer diagnosticCommandServer(
        AtomicReference<String> invokedOperation,
        AtomicReference<String[]> invokedArguments,
        String threadDump
    ) {
        MBeanInfo mBeanInfo = new MBeanInfo(
            "diagnostic-command",
            "diagnostic-command",
            null,
            null,
            new MBeanOperationInfo[] {
                new MBeanOperationInfo("threadPrint", "threadPrint", null, String.class.getName(), MBeanOperationInfo.INFO)
            },
            null
        );
        return (MBeanServer) Proxy.newProxyInstance(
            VmDiagnosticsTest.class.getClassLoader(),
            new Class<?>[] { MBeanServer.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "isRegistered" -> true;
                case "getMBeanInfo" -> mBeanInfo;
                case "invoke" -> {
                    invokedOperation.set((String) args[1]);
                    Object[] parameters = (Object[]) args[2];
                    invokedArguments.set((String[]) parameters[0]);
                    yield threadDump;
                }
                default -> unsupported(method.getName());
            }
        );
    }

    private static MBeanServer unavailableDiagnosticCommandServer() {
        return (MBeanServer) Proxy.newProxyInstance(
            VmDiagnosticsTest.class.getClassLoader(),
            new Class<?>[] { MBeanServer.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "isRegistered" -> false;
                default -> unsupported(method.getName());
            }
        );
    }

    private static BootLensDiagnosticsProperties defaultProperties() {
        BootLensDiagnosticsProperties properties = new BootLensDiagnosticsProperties();
        properties.setAllowSensitive(true);
        properties.setSanitizePrivacy(true);
        properties.setSanitizeSecrets(true);
        properties.setIncludeClasspath(false);
        return properties;
    }

    private static void withSystemProperty(String key, String value, ThrowingRunnable runnable) throws Exception {
        String original = System.getProperty(key);
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }

        try {
            runnable.run();
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Object unsupported(String methodName) {
        throw new UnsupportedOperationException("Unexpected MBeanServer call: " + methodName);
    }
}
