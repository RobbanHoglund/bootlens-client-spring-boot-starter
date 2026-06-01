package com.bootlens.client.diagnostics;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SecretSanitizer {

    private static final String SECRET_MASK = "******";
    private static final String PRIVACY_REDACTION = "<redacted>";
    private static final String CLASSPATH_OMITTED = "<classpath omitted>";

    private static final Set<String> SENSITIVE_TERMS = Set.of(
        "password",
        "passwd",
        "pwd",
        "secret",
        "token",
        "api_key",
        "apikey",
        "access_key",
        "secret_key",
        "credential",
        "credentials",
        "private_key",
        "client_secret",
        "authorization",
        "cookie",
        "session",
        // Connection string patterns — DATABASE_URL, REDIS_URL, JDBC_URL, etc.
        // carry embedded credentials and must be masked when they appear in key names.
        "url",
        "dsn",
        "connection"
    );

    private static final Set<String> PRIVACY_KEYS = Set.of(
        "java.home",
        "user.name",
        "user.home",
        "user.dir",
        "java.io.tmpdir",
        "java.library.path",
        "sun.java.command",
        "catalina.base",
        "catalina.home",
        "app.home",
        "appdata",
        "localappdata",
        "userprofile",
        "homepath",
        "home",
        "pwd",
        "tmp",
        "temp",
        "computername"
    );

    private static final Set<String> CLASSPATH_KEYS = Set.of(
        "java.class.path",
        "class.path",
        "classpath"
    );

    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
        "(?im)^(\\s*[\"']?([A-Za-z0-9._\\- ]+)[\"']?\\s*)([:=])(\\s*)(.*)$"
    );

    private static final Pattern JVM_ARGUMENT_PATTERN = Pattern.compile(
        "(?im)^(\\s*-D)([A-Za-z0-9._\\-]+)(=)(.*)$"
    );

    private static final Pattern INLINE_PROPERTY_PATTERN = Pattern.compile(
        "(?i)(-D)?([A-Za-z0-9._\\-]+)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;]+)"
    );

    private final BootLensDiagnosticsProperties properties;

    public SecretSanitizer() {
        this(defaultProperties());
    }

    public SecretSanitizer(BootLensDiagnosticsProperties properties) {
        this.properties = properties;
    }

    public String sanitizeEntry(String key, String value) {
        if (value == null) {
            return null;
        }

        if (!properties.isSanitizeSecrets() && !properties.isSanitizePrivacy()) {
            return value;
        }

        if (properties.isSanitizeSecrets() && isSensitiveKey(key)) {
            return SECRET_MASK;
        }

        if (isClasspathKey(key) && !properties.isIncludeClasspath()) {
            return CLASSPATH_OMITTED;
        }

        if (properties.isSanitizePrivacy() && isPrivacyKey(key)) {
            return PRIVACY_REDACTION;
        }

        return value;
    }

    public String sanitizeProperties(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        return sanitizeStructuredText(text, true);
    }

    public String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        return sanitizeStructuredText(text, false);
    }

    private String sanitizeStructuredText(String text, boolean lineStrict) {
        StringBuilder sanitized = new StringBuilder();
        String[] lines = text.split("\\R", -1);

        for (int index = 0; index < lines.length; index++) {
            sanitized.append(sanitizeLine(lines[index], lineStrict));
            if (index < lines.length - 1) {
                sanitized.append(System.lineSeparator());
            }
        }

        return sanitized.toString();
    }

    private String sanitizeLine(String line, boolean lineStrict) {
        Matcher jvmArgumentMatcher = JVM_ARGUMENT_PATTERN.matcher(line);
        if (jvmArgumentMatcher.matches()) {
            String key = jvmArgumentMatcher.group(2);
            String sanitizedValue = sanitizeEntry(key, jvmArgumentMatcher.group(4));
            return jvmArgumentMatcher.group(1)
                + key
                + jvmArgumentMatcher.group(3)
                + sanitizedValue;
        }

        Matcher keyValueMatcher = KEY_VALUE_PATTERN.matcher(line);
        if (keyValueMatcher.matches()) {
            String key = keyValueMatcher.group(2);
            String sanitizedValue = sanitizeEntry(key, keyValueMatcher.group(5));
            return keyValueMatcher.group(1)
                + keyValueMatcher.group(3)
                + keyValueMatcher.group(4)
                + sanitizedValue;
        }

        if (lineStrict) {
            return line;
        }

        Matcher inlinePropertyMatcher = INLINE_PROPERTY_PATTERN.matcher(line);
        StringBuffer sanitized = new StringBuffer();
        while (inlinePropertyMatcher.find()) {
            String key = inlinePropertyMatcher.group(2);
            String value = trimQuotes(inlinePropertyMatcher.group(4));
            String sanitizedValue = sanitizeEntry(key, value);
            String quotePrefix = startsWithQuote(inlinePropertyMatcher.group(4)) ? inlinePropertyMatcher.group(4).substring(0, 1) : "";
            String quoteSuffix = endsWithMatchingQuote(inlinePropertyMatcher.group(4)) ? inlinePropertyMatcher.group(4).substring(inlinePropertyMatcher.group(4).length() - 1) : "";
            String replacement = (inlinePropertyMatcher.group(1) == null ? "" : inlinePropertyMatcher.group(1))
                + key
                + inlinePropertyMatcher.group(3)
                + quotePrefix
                + sanitizedValue
                + quoteSuffix;
            inlinePropertyMatcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        inlinePropertyMatcher.appendTail(sanitized);
        return sanitized.toString();
    }

    private String trimQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }

        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private boolean startsWithQuote(String value) {
        return value != null && !value.isEmpty() && (value.charAt(0) == '"' || value.charAt(0) == '\'');
    }

    private boolean endsWithMatchingQuote(String value) {
        return value != null
            && value.length() >= 2
            && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''));
    }

    private boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        String normalized = normalizeForContains(key);
        return SENSITIVE_TERMS.stream()
            .filter(Objects::nonNull)
            .anyMatch(normalized::contains);
    }

    private boolean isPrivacyKey(String key) {
        return PRIVACY_KEYS.contains(canonicalizeKey(key));
    }

    private boolean isClasspathKey(String key) {
        return CLASSPATH_KEYS.contains(canonicalizeKey(key));
    }

    private String canonicalizeKey(String key) {
        if (key == null) {
            return "";
        }

        return Arrays.stream(key.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(part -> !part.isBlank())
            .collect(Collectors.joining("."));
    }

    private String normalizeForContains(String key) {
        Set<String> parts = Arrays.stream(key.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(part -> !part.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        if (parts.isEmpty()) {
            return key.toLowerCase(Locale.ROOT);
        }

        return String.join("_", parts);
    }

    private static BootLensDiagnosticsProperties defaultProperties() {
        return new BootLensDiagnosticsProperties();
    }
}
