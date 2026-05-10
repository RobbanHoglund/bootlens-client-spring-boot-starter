package com.bootlens.client.registration;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;
import org.springframework.core.env.Environment;

public class BootLensRegistrationClient {

    private static final Logger log = LoggerFactory.getLogger(BootLensRegistrationClient.class);
    private static final Pattern NON_IDENTIFIER = Pattern.compile("[^a-z0-9]+");
    private static final String DEFAULT_SERVER_URL = "http://localhost:9090";
    private static final String DEFAULT_BASE_HOST = "http://localhost";
    private static final String DEFAULT_ACTUATOR_BASE_PATH = "/actuator";

    private final BootLensRegistrationProperties properties;
    private final Environment environment;
    private final RegistrationTransport transport;
    private final Clock clock;

    public BootLensRegistrationClient(
        BootLensRegistrationProperties properties,
        Environment environment
    ) {
        this(properties, environment, new HttpRegistrationTransport(), Clock.systemUTC());
    }

    BootLensRegistrationClient(
        BootLensRegistrationProperties properties,
        Environment environment,
        RegistrationTransport transport,
        Clock clock
    ) {
        this.properties = properties;
        this.environment = environment;
        this.transport = transport;
        this.clock = clock;
    }

    RegistrationCallResult register() {
        BootLensResolvedRegistration registration = resolveRegistration();
        String url = registration.serverUrl() + "/api/registry/instances";
        return execute("register", registration.instanceId(), () -> transport.post(url, toJson(registration.toRegistrationRequest()), authorizationHeader()));
    }

    RegistrationCallResult heartbeat() {
        BootLensResolvedRegistration registration = resolveRegistration();
        String url = registration.serverUrl() + "/api/registry/instances/" + registration.instanceId() + "/heartbeat";
        return execute("heartbeat", registration.instanceId(), () -> transport.post(url, toJson(registration.toHeartbeatRequest()), authorizationHeader()));
    }

    RegistrationCallResult deregister() {
        BootLensResolvedRegistration registration = resolveRegistration();
        String url = registration.serverUrl() + "/api/registry/instances/" + registration.instanceId();
        return execute("deregister", registration.instanceId(), () -> transport.delete(url, authorizationHeader()));
    }

    BootLensRegistrationRequest buildRegistrationRequest() {
        return resolveRegistration().toRegistrationRequest();
    }

    String heartbeatPath() {
        BootLensResolvedRegistration registration = resolveRegistration();
        return registration.serverUrl() + "/api/registry/instances/" + registration.instanceId() + "/heartbeat";
    }

    String resolvedInstanceId() {
        return resolveRegistration().instanceId();
    }

    private RegistrationCallResult execute(String action, String instanceId, ThrowingSupplier supplier) {
        try {
            RegistrationCallResult result = supplier.get();
            if (result.success()) {
                return result;
            }
            if (result.notFound()) {
                log.debug("BootLens {} endpoint reported missing registration for {}: {}", action, instanceId, result.message());
            }
            return result;
        }
        catch (IOException exception) {
            log.debug("BootLens {} call failed for {}: {}", action, instanceId, exception.getMessage());
            return RegistrationCallResult.failure(503, exception.getMessage());
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.debug("BootLens {} call interrupted for {}: {}", action, instanceId, exception.getMessage());
            return RegistrationCallResult.failure(503, exception.getMessage());
        }
        catch (RuntimeException exception) {
            log.debug("BootLens {} call failed unexpectedly for {}: {}", action, instanceId, exception.getMessage());
            return RegistrationCallResult.failure(500, exception.getMessage());
        }
    }

    /**
     * Builds the HTTP Basic authorization header used when the BootLens server
     * protects its registry endpoints.
     */
    String authorizationHeader() {
        String username = firstNonBlank(properties.getUsername(), null);
        String password = firstNonBlank(properties.getPassword(), null);
        if (isBlank(username) || password == null) {
            return null;
        }
        String token = Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private BootLensResolvedRegistration resolveRegistration() {
        String serverUrl = trimTrailingSlash(firstNonBlank(properties.getServerUrl(), DEFAULT_SERVER_URL));
        String appName = firstNonBlank(properties.getAppName(), environment.getProperty("spring.application.name", "application"));
        String appId = firstNonBlank(properties.getAppId(), normalizeAppId(appName));
        String hostname = resolveHostname();
        String serverPort = resolveServerPort();
        String managementPort = resolveManagementPort(serverPort);
        String baseUrl = trimTrailingSlash(firstNonBlank(properties.getBaseUrl(), DEFAULT_BASE_HOST + ":" + serverPort));
        String actuatorBaseUrl = trimTrailingSlash(
            firstNonBlank(
                properties.getActuatorBaseUrl(),
                DEFAULT_BASE_HOST + ":" + managementPort + resolveActuatorBasePath()
            )
        );
        String environmentName = resolveEnvironment();
        String instanceId = firstNonBlank(properties.getInstanceId(), stableInstanceId(appId, hostname, serverPort));
        String displayName = firstNonBlank(properties.getDisplayName(), instanceId);
        String region = firstNonBlank(properties.getRegion(), null);
        String team = firstNonBlank(properties.getTeam(), null);
        String zone = firstNonBlank(properties.getZone(), null);
        String slot = firstNonBlank(properties.getSlot(), null);
        Map<String, String> labels = buildLabels(environmentName, region, team, zone, slot);
        Map<String, String> metadata = buildMetadata(hostname);

        return new BootLensResolvedRegistration(
            serverUrl,
            instanceId,
            appId,
            appName,
            displayName,
            baseUrl,
            actuatorBaseUrl,
            environmentName,
            region,
            team,
            zone,
            slot,
            labels,
            metadata
        );
    }

    private Map<String, String> buildLabels(
        String environmentName,
        String region,
        String team,
        String zone,
        String slot
    ) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (properties.getLabels() != null) {
            labels.putAll(properties.getLabels());
        }
        putIfPresent(labels, "env", environmentName);
        putIfPresent(labels, "region", region);
        putIfPresent(labels, "team", team);
        putIfPresent(labels, "zone", zone);
        putIfPresent(labels, "slot", slot);
        return Map.copyOf(labels);
    }

    private Map<String, String> buildMetadata(String hostname) {
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> gcNames = resolveGcNames();
        String gcFamily = inferGcFamily(gcNames);
        String javaVersion = System.getProperty("java.version");
        String cacheBackend = resolveCacheBackend();
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "javaVersion", javaVersion);
        putIfPresent(metadata, "javaVendor", System.getProperty("java.vendor"));
        putIfPresent(metadata, "springBootVersion", SpringBootVersion.getVersion());
        putIfPresent(metadata, "springFrameworkVersion", SpringVersion.getVersion());
        putIfPresent(metadata, "pid", resolvePid());
        putIfPresent(metadata, "hostname", hostname);
        putIfPresent(metadata, "activeProfiles", String.join(",", environment.getActiveProfiles()));
        putIfPresent(metadata, "startedAt", Instant.ofEpochMilli(runtimeMxBean.getStartTime()).toString());
        putIfPresent(metadata, "uptimeMs", Long.toString(runtimeMxBean.getUptime()));
        putIfPresent(metadata, "gcNames", String.join(",", gcNames));
        putIfPresent(metadata, "gcFamily", gcFamily);
        putIfPresent(metadata, "runtimeVmName", runtimeMxBean.getVmName());
        putIfPresent(metadata, "runtimeVmVendor", runtimeMxBean.getVmVendor());
        putIfPresent(metadata, "runtimeVmVersion", runtimeMxBean.getVmVersion());
        putIfPresent(metadata, "availableProcessors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        putIfPresent(metadata, "cacheBackend", cacheBackend);
        putIfPresent(metadata, "tags", String.join(",", buildTags(javaVersion, gcFamily, cacheBackend)));
        putIfPresent(metadata, "capturedAt", clock.instant().toString());
        return Map.copyOf(metadata);
    }

    private String resolveServerPort() {
        return firstNonBlank(
            environment.getProperty("local.server.port"),
            firstNonBlank(environment.getProperty("server.port"), "8080")
        );
    }

    private String resolveManagementPort(String serverPort) {
        return firstNonBlank(
            environment.getProperty("local.management.port"),
            firstNonBlank(environment.getProperty("management.server.port"), serverPort)
        );
    }

    private String resolveActuatorBasePath() {
        String configuredBasePath = firstNonBlank(
            environment.getProperty("management.endpoints.web.base-path"),
            DEFAULT_ACTUATOR_BASE_PATH
        );
        return configuredBasePath.startsWith("/") ? configuredBasePath : "/" + configuredBasePath;
    }

    private String resolveEnvironment() {
        if (!isBlank(properties.getEnvironment())) {
            return properties.getEnvironment().trim();
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            return activeProfiles[0];
        }
        return "local";
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (UnknownHostException exception) {
            return "localhost";
        }
    }

    private String resolvePid() {
        return Long.toString(ProcessHandle.current().pid());
    }

    private List<String> resolveGcNames() {
        List<String> names = new ArrayList<>();
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcBean.getName() != null && !gcBean.getName().isBlank()) {
                names.add(gcBean.getName());
            }
        }
        return List.copyOf(names);
    }

    private String inferGcFamily(List<String> gcNames) {
        if (gcNames.isEmpty()) {
            return null;
        }

        String joined = String.join(" ", gcNames).toLowerCase(Locale.ROOT);
        if (joined.contains("g1")) {
            return "G1";
        }
        if (joined.contains("zgc") || joined.contains("z garbage collector")) {
            return "ZGC";
        }
        if (joined.contains("shenandoah")) {
            return "Shenandoah";
        }
        if (joined.contains("parallel")) {
            return "Parallel";
        }
        if (joined.contains("serial") || (joined.contains("copy") && joined.contains("marksweepcompact"))) {
            return "Serial";
        }
        return String.join(" / ", gcNames);
    }

    private String resolveCacheBackend() {
        String configuredBackend = firstNonBlank(
            environment.getProperty("demo.cache.backend"),
            environment.getProperty("spring.cache.type")
        );
        if (isBlank(configuredBackend)) {
            return null;
        }
        return configuredBackend.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> buildTags(String javaVersion, String gcFamily, String cacheBackend) {
        Set<String> tags = new LinkedHashSet<>();
        addTag(tags, "env", resolveEnvironment());
        addTag(tags, "region", properties.getRegion());
        addTag(tags, "team", properties.getTeam());
        addTag(tags, "zone", properties.getZone());
        addTag(tags, "slot", properties.getSlot());

        String tier = firstNonBlank(properties.getLabels().get("tier"), environment.getProperty("bootlens.client.registration.labels.tier"));
        addTag(tags, "tier", tier);
        String topology = firstNonBlank(properties.getLabels().get("topology"), environment.getProperty("bootlens.client.registration.labels.topology"));
        addTag(tags, "topology", topology);
        addTag(tags, "cache", cacheBackend);
        addTag(tags, "gc", gcFamily == null ? null : gcFamily.toLowerCase(Locale.ROOT));
        addTag(tags, "java", resolveJavaMajorVersion(javaVersion));
        for (String profile : environment.getActiveProfiles()) {
            addTag(tags, "profile", profile);
        }
        return List.copyOf(tags);
    }

    private String resolveJavaMajorVersion(String javaVersion) {
        if (isBlank(javaVersion)) {
            return null;
        }
        String sanitized = javaVersion.trim();
        int separator = sanitized.indexOf('.');
        return separator > 0 ? sanitized.substring(0, separator) : sanitized;
    }

    private void addTag(Set<String> tags, String key, String value) {
        if (isBlank(key) || isBlank(value)) {
            return;
        }
        tags.add(key.trim().toLowerCase(Locale.ROOT) + "=" + normalizeTagValue(value));
    }

    private String normalizeTagValue(String value) {
        String normalized = NON_IDENTIFIER.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private String stableInstanceId(String appId, String hostname, String port) {
        String normalizedHost = normalizeIdentifier(hostname);
        if (!isBlank(port)) {
            return appId + "-" + normalizedHost + "-" + port;
        }
        return appId
            + "-"
            + normalizedHost
            + "-"
            + UUID.nameUUIDFromBytes((appId + hostname).getBytes()).toString().substring(0, 8);
    }

    private String normalizeAppId(String appName) {
        return normalizeIdentifier(appName);
    }

    private String normalizeIdentifier(String value) {
        String normalized = NON_IDENTIFIER.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        return normalized.isBlank() ? "application" : normalized;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first.trim() : (!isBlank(second) ? second.trim() : null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value);
        }
    }

    private String toJson(BootLensRegistrationRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        appendField(builder, "instanceId", request.instanceId(), true);
        appendField(builder, "appId", request.appId(), false);
        appendField(builder, "appName", request.appName(), false);
        appendField(builder, "displayName", request.displayName(), false);
        appendField(builder, "baseUrl", request.baseUrl(), false);
        appendField(builder, "actuatorBaseUrl", request.actuatorBaseUrl(), false);
        appendField(builder, "environment", request.environment(), false);
        appendField(builder, "region", request.region(), false);
        appendField(builder, "team", request.team(), false);
        appendField(builder, "zone", request.zone(), false);
        appendField(builder, "slot", request.slot(), false);
        appendMapField(builder, "labels", request.labels(), false);
        appendMapField(builder, "metadata", request.metadata(), false);
        builder.append('}');
        return builder.toString();
    }

    private String toJson(BootLensHeartbeatRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        appendField(builder, "status", request.status(), true);
        appendMapField(builder, "metadata", request.metadata(), false);
        builder.append('}');
        return builder.toString();
    }

    private void appendField(StringBuilder builder, String key, String value, boolean first) {
        if (!first) {
            builder.append(',');
        }
        builder.append('"').append(escapeJson(key)).append('"').append(':');
        if (value == null) {
            builder.append("null");
        }
        else {
            builder.append('"').append(escapeJson(value)).append('"');
        }
    }

    private void appendMapField(StringBuilder builder, String key, Map<String, String> values, boolean first) {
        if (!first) {
            builder.append(',');
        }
        builder.append('"').append(escapeJson(key)).append('"').append(':').append('{');
        if (values != null) {
            boolean firstEntry = true;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!firstEntry) {
                    builder.append(',');
                }
                builder.append('"').append(escapeJson(entry.getKey())).append('"').append(':');
                if (entry.getValue() == null) {
                    builder.append("null");
                }
                else {
                    builder.append('"').append(escapeJson(entry.getValue())).append('"');
                }
                firstEntry = false;
            }
        }
        builder.append('}');
    }

    private String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        RegistrationCallResult get() throws IOException, InterruptedException;
    }
}
