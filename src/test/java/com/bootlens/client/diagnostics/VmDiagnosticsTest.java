package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

class VmDiagnosticsTest {

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
}
