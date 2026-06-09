package com.bootlens.client.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BootLensRegistrationClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-04T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultAppIdIsDerivedFromSpringApplicationName() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.application.name", "BootLens Demo App")
            .withProperty("server.port", "9091");

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            new CapturingTransport(),
            FIXED_CLOCK
        );

        BootLensRegistrationRequest request = client.buildRegistrationRequest();

        assertThat(request.appName()).isEqualTo("BootLens Demo App");
        assertThat(request.appId()).isEqualTo("bootlens-demo-app");
    }

    @Test
    void plaintextTransportWithCredentialsIsFlaggedOnlyForHttpUrls() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setUsername("registry-user");
        properties.setPassword("registry-secret");
        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            new MockEnvironment(),
            new CapturingTransport(),
            FIXED_CLOCK
        );

        assertThat(client.isPlaintextTransportWithCredentials("http://bootlens.internal:9090")).isTrue();
        assertThat(client.isPlaintextTransportWithCredentials("https://bootlens.internal:9090")).isFalse();
    }

    @Test
    void plaintextTransportIsNotFlaggedWithoutCredentials() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            new MockEnvironment(),
            new CapturingTransport(),
            FIXED_CLOCK
        );

        assertThat(client.isPlaintextTransportWithCredentials("http://bootlens.internal:9090")).isFalse();
    }

    @Test
    void stableInstanceIdUsesResolvedAppIdAndPort() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setAppId("bootlens-demo");
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "9091");

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            new CapturingTransport(),
            FIXED_CLOCK
        );

        String firstInstanceId = client.resolvedInstanceId();
        String secondInstanceId = client.resolvedInstanceId();

        assertThat(firstInstanceId).isEqualTo(secondInstanceId);
        assertThat(firstInstanceId).startsWith("bootlens-demo-");
        assertThat(firstInstanceId).endsWith("-9091");
    }

    @Test
    void registrationRequestContainsActuatorBaseUrl() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setAppName("BootLens Demo");
        MockEnvironment environment = new MockEnvironment()
            .withProperty("server.port", "9091")
            .withProperty("management.server.port", "9191");

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            new CapturingTransport(),
            FIXED_CLOCK
        );

        BootLensRegistrationRequest request = client.buildRegistrationRequest();

        assertThat(request.actuatorBaseUrl()).isEqualTo("http://localhost:9191/actuator");
    }

    @Test
    void registrationRequestIncludesOptionalActuatorCredentials() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setActuatorUsername("actuator-user");
        properties.setActuatorPassword("actuator-secret");
        MockEnvironment environment = new MockEnvironment()
            .withProperty("server.port", "9091");

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            new CapturingTransport(),
            FIXED_CLOCK
        );

        BootLensRegistrationRequest request = client.buildRegistrationRequest();

        assertThat(request.actuatorUsername()).isEqualTo("actuator-user");
        assertThat(request.actuatorPassword()).isEqualTo("actuator-secret");
    }

    @Test
    void heartbeatCallPathIsCorrect() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setServerUrl("http://bootlens.example:9090/");
        properties.setInstanceId("bootlens-demo-local-9091");
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "9091");
        CapturingTransport transport = new CapturingTransport();

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            transport,
            FIXED_CLOCK
        );

        RegistrationCallResult result = client.heartbeat();

        assertThat(result.success()).isTrue();
        assertThat(transport.lastPostUrl).isEqualTo(
            "http://bootlens.example:9090/api/registry/instances/bootlens-demo-local-9091/heartbeat"
        );
    }

    @Test
    void registrationAuthHeaderUsesConfiguredCredentials() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setUsername("registrant");
        properties.setPassword("registrant-local");
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "9091");
        CapturingTransport transport = new CapturingTransport();

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            transport,
            FIXED_CLOCK
        );

        client.register();

        assertThat(transport.lastAuthorizationHeader).isEqualTo(
            "Basic " + Base64.getEncoder().encodeToString("registrant:registrant-local".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void registrationAuthFailureSurfacesAsNonSuccessResult() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setUsername("registrant");
        properties.setPassword("wrong-password");
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "9091");
        CapturingTransport transport = new CapturingTransport();
        transport.nextPostResult = RegistrationCallResult.failure(401, "Unauthorized");

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            transport,
            FIXED_CLOCK
        );

        RegistrationCallResult result = client.register();

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(401);
        assertThat(result.message()).isEqualTo("Unauthorized");
    }

    @Test
    void heartbeatNotFoundSurfacesAsReRegistrationSignal() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setInstanceId("bootlens-demo-local-9091");
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "9091");
        CapturingTransport transport = new CapturingTransport();
        transport.nextPostResult = RegistrationCallResult.notFound("Unknown instance");

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            transport,
            FIXED_CLOCK
        );

        RegistrationCallResult result = client.heartbeat();

        assertThat(result.success()).isFalse();
        assertThat(result.notFound()).isTrue();
        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(result.message()).isEqualTo("Unknown instance");
    }

    @Test
    void heartbeatAuthFailureSurfacesAsNonSuccessResult() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setInstanceId("bootlens-demo-local-9091");
        properties.setUsername("registrant");
        properties.setPassword("wrong-password");
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "9091");
        CapturingTransport transport = new CapturingTransport();
        transport.nextPostResult = RegistrationCallResult.failure(401, "Unauthorized");

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            transport,
            FIXED_CLOCK
        );

        RegistrationCallResult result = client.heartbeat();

        assertThat(result.success()).isFalse();
        assertThat(result.notFound()).isFalse();
        assertThat(result.statusCode()).isEqualTo(401);
        assertThat(result.message()).isEqualTo("Unauthorized");
    }

    @Test
    void registrationMetadataIncludesRuntimeAndOperationalTags() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setEnvironment("local");
        properties.setRegion("eu-local");
        properties.setTeam("platform");
        properties.setZone("lab-a");
        properties.setSlot("primary");
        properties.setLabels(Map.of("tier", "backend", "topology", "single-instance"));
        properties.setCacheBackend("caffeine"); // use the proper property (not demo.cache.backend)
        MockEnvironment environment = new MockEnvironment()
            .withProperty("server.port", "9091")
            .withProperty("spring.application.name", "Spinning Threads App")
            .withProperty("spring.profiles.active", "local");
        environment.setActiveProfiles("local");

        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            environment,
            new CapturingTransport(),
            FIXED_CLOCK
        );

        BootLensRegistrationRequest request = client.buildRegistrationRequest();

        assertThat(request.metadata()).containsKeys(
            "startedAt",
            "uptimeMs",
            "pid",
            "gcNames",
            "gcFamily",
            "runtimeVmName",
            "runtimeVmVendor",
            "runtimeVmVersion",
            "availableProcessors",
            "cacheBackend",
            "tags"
        );
        assertThat(request.metadata().get("cacheBackend")).isEqualTo("caffeine");
        assertThat(request.metadata().get("tags")).contains("env=local");
        assertThat(request.metadata().get("tags")).contains("region=eu-local");
        assertThat(request.metadata().get("tags")).contains("team=platform");
        assertThat(request.metadata().get("tags")).contains("cache=caffeine");
        assertThat(request.metadata().get("tags")).contains("java=");
    }

    @Test
    void infersSerialGcFamilyFromCopyAndMarkSweepCompactCollectors() throws Exception {
        BootLensRegistrationClient client = new BootLensRegistrationClient(
            new BootLensRegistrationProperties(),
            new MockEnvironment().withProperty("server.port", "9091"),
            new CapturingTransport(),
            FIXED_CLOCK
        );

        Method inferGcFamily = BootLensRegistrationClient.class.getDeclaredMethod("inferGcFamily", List.class);
        inferGcFamily.setAccessible(true);

        String gcFamily = (String) inferGcFamily.invoke(client, List.of("Copy", "MarkSweepCompact"));

        assertThat(gcFamily).isEqualTo("Serial");
    }

    private static final class CapturingTransport implements RegistrationTransport {

        private String lastPostUrl;
        private String lastAuthorizationHeader;
        private RegistrationCallResult nextPostResult = RegistrationCallResult.success(200);

        @Override
        public RegistrationCallResult post(String url, String jsonBody, String authorizationHeader) {
            this.lastPostUrl = url;
            this.lastAuthorizationHeader = authorizationHeader;
            return nextPostResult;
        }

        @Override
        public RegistrationCallResult delete(String url, String authorizationHeader) {
            return RegistrationCallResult.success(204);
        }
    }
}
