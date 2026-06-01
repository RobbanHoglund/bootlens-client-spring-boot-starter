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

    // -------------------------------------------------------------------------
    // Connection string / URL patterns (P0-2 fix)
    // -------------------------------------------------------------------------

    @Test
    void masksDatabaseUrlEnvironmentVariable() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        // Cloud platforms (Railway, Heroku, Render, Fly) inject DATABASE_URL as a
        // single connection string with embedded credentials. It must be masked.
        assertThat(sanitizer.sanitizeEntry("DATABASE_URL", "postgres://user:s3cr3t@host/db"))
            .isEqualTo("******");
    }

    @Test
    void masksRedisUrl() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("REDIS_URL", "redis://:s3cr3t@host:6379"))
            .isEqualTo("******");
    }

    @Test
    void masksMongoUrl() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("MONGO_URL", "mongodb://user:pass@host/db"))
            .isEqualTo("******");
    }

    @Test
    void masksJdbcUrl() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("JDBC_URL", "jdbc:postgresql://user:pass@host/db"))
            .isEqualTo("******");
    }

    @Test
    void masksDatabaseDsn() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("DB_DSN", "sqlite:///tmp/db.sqlite"))
            .isEqualTo("******");
    }

    @Test
    void masksConnectionString() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        assertThat(sanitizer.sanitizeEntry("DB_CONNECTION_STRING", "Server=host;Password=s3cr3t;"))
            .isEqualTo("******");
    }

    @Test
    void masksSpringDatasourceUrl() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();

        // -Dspring.datasource.url=jdbc:postgresql://user:pass@host/db
        assertThat(sanitizer.sanitizeEntry("spring.datasource.url",
            "jdbc:postgresql://user:pass@host/db"))
            .isEqualTo("******");
    }

    @Test
    void masksConnectionStringInEnvOutput() {
        SecretSanitizer sanitizer = sanitizerWithDefaults();
        String envLine = "DATABASE_URL=postgres://user:s3cr3t@host/db";

        String sanitized = sanitizer.sanitizeProperties(envLine);

        assertThat(sanitized).contains("DATABASE_URL=******");
        assertThat(sanitized).doesNotContain("s3cr3t");
    }

    private static SecretSanitizer sanitizerWithDefaults() {
        return new SecretSanitizer(defaultProperties());
    }

    private static BootLensDiagnosticsProperties defaultProperties() {
        return new BootLensDiagnosticsProperties();
    }
}
