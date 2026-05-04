package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretSanitizerTest {

    @Test
    void masksClassicSecrets() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("db.password", "s3cr3t")).isEqualTo("******");
        assertThat(sanitizer.sanitizeEntry("api.token", "abc123")).isEqualTo("******");
    }

    @Test
    void redactsUserNameWhenPrivacySanitizationEnabled() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("user.name", "robba")).isEqualTo("<redacted>");
    }

    @Test
    void redactsUserHomeWhenPrivacySanitizationEnabled() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("user.home", "C:\\Users\\robba")).isEqualTo("<redacted>");
    }

    @Test
    void redactsUserDirWhenPrivacySanitizationEnabled() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("user.dir", "C:\\ws\\git\\bootlens-demo-app")).isEqualTo("<redacted>");
    }

    @Test
    void omitsJavaClasspathWhenDisabled() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("java.class.path", "app.jar;libs\\a.jar")).isEqualTo("<classpath omitted>");
    }

    @Test
    void keepsJavaClasspathWhenExplicitlyEnabled() {
        BootLensDiagnosticsProperties properties = defaultProperties();
        properties.setIncludeClasspath(true);
        SecretSanitizer sanitizer = new SecretSanitizer(properties);

        assertThat(sanitizer.sanitizeEntry("java.class.path", "app.jar;libs\\a.jar")).isEqualTo("app.jar;libs\\a.jar");
    }

    @Test
    void redactsEnvironmentPrivacyKeys() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("USERPROFILE", "C:\\Users\\robba")).isEqualTo("<redacted>");
    }

    @Test
    void preservesNonSensitiveNormalKey() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("java.version", "25.0.1")).isEqualTo("25.0.1");
    }

    @Test
    void sanitizesPropertiesTextWithSecretsPrivacyAndClasspathRules() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();
        String input = String.join(
            System.lineSeparator(),
            "user.name=robba",
            "user.home=C:\\Users\\robba",
            "java.class.path=app.jar;libs\\a.jar",
            "db.password=s3cr3t",
            "java.version=25.0.1"
        );

        String sanitized = sanitizer.sanitizeProperties(input);

        assertThat(sanitized).contains("user.name=<redacted>");
        assertThat(sanitized).contains("user.home=<redacted>");
        assertThat(sanitized).contains("java.class.path=<classpath omitted>");
        assertThat(sanitized).contains("db.password=******");
        assertThat(sanitized).contains("java.version=25.0.1");
    }

    @Test
    void sanitizesJvmArgumentStyleText() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();
        String input = "-Duser.dir=C:\\ws\\git\\bootlens-demo-app" + System.lineSeparator()
            + "-Ddb.password=s3cr3t";

        String sanitized = sanitizer.sanitizeText(input);

        assertThat(sanitized).contains("-Duser.dir=<redacted>");
        assertThat(sanitized).contains("-Ddb.password=******");
    }

    @Test
    void sanitizesSecurityReportStyleText() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();
        String input = String.join(
            System.lineSeparator(),
            "user.name=robba",
            "user.home=C:\\Users\\robba",
            "java.class.path=app.jar;libs\\a.jar",
            "javax.net.ssl.trustStore=C:\\certs\\truststore.p12"
        );

        String sanitized = sanitizer.sanitizeText(input);

        assertThat(sanitized).contains("user.name=<redacted>");
        assertThat(sanitized).contains("user.home=<redacted>");
        assertThat(sanitized).contains("java.class.path=<classpath omitted>");
        assertThat(sanitized).contains("javax.net.ssl.trustStore=C:\\certs\\truststore.p12");
    }

    @Test
    void sanitizesCommandLineStyleKeyValueText() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();
        String input = String.join(
            System.lineSeparator(),
            "sun.java.command=com.bootlens.demo.BootlensDemoAppApplication --spring.config.location=C:\\apps\\demo\\config\\",
            "java.class.path=app.jar;libs\\a.jar",
            "java.library.path=C:\\java\\bin"
        );

        String sanitized = sanitizer.sanitizeText(input);

        assertThat(sanitized).contains("sun.java.command=<redacted>");
        assertThat(sanitized).contains("java.class.path=<classpath omitted>");
        assertThat(sanitized).contains("java.library.path=<redacted>");
    }

    private static SecretSanitizer sanitizerWithDefaults() {
        return new SecretSanitizer(defaultProperties());
    }

    private static BootLensDiagnosticsProperties defaultProperties() {
        return new BootLensDiagnosticsProperties();
    }
}
