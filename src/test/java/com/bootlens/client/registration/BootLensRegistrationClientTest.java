package com.bootlens.client.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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

    private static final class CapturingTransport implements RegistrationTransport {

        private String lastPostUrl;

        @Override
        public RegistrationCallResult post(String url, String jsonBody) {
            this.lastPostUrl = url;
            return RegistrationCallResult.success(200);
        }

        @Override
        public RegistrationCallResult delete(String url) {
            return RegistrationCallResult.success(204);
        }
    }
}
