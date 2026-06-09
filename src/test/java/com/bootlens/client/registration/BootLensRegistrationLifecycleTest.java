package com.bootlens.client.registration;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BootLensRegistrationLifecycleTest {

    @Test
    void startupRegistrationFailureDoesNotEscapeToHostApplication() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setRegisterOnStartup(true);
        // A client whose resolution blows up with an unchecked exception simulates any
        // unexpected failure (DNS lookup, MBean access, etc.) on the startup path.
        BootLensRegistrationClient throwingClient =
            new BootLensRegistrationClient(properties, new MockEnvironment()) {
                @Override
                String resolvedCallbackCredentialSummary() {
                    throw new IllegalStateException("boom");
                }
            };
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        BootLensRegistrationLifecycle lifecycle =
            new BootLensRegistrationLifecycle(properties, throwingClient, executorService);

        // onApplicationEvent ignores the event instance; passing null keeps the test light.
        assertThatCode(() -> lifecycle.onApplicationEvent(null)).doesNotThrowAnyException();

        executorService.shutdownNow();
    }

    @Test
    void shutdownDeregisterIsBestEffort() {
        BootLensRegistrationProperties properties = new BootLensRegistrationProperties();
        properties.setInstanceId("bootlens-demo-local-9091");
        BootLensRegistrationClient client = new BootLensRegistrationClient(
            properties,
            new MockEnvironment().withProperty("server.port", "9091"),
            new FailingDeleteTransport(),
            Clock.fixed(Instant.parse("2026-05-04T12:00:00Z"), ZoneOffset.UTC)
        );
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        BootLensRegistrationLifecycle lifecycle = new BootLensRegistrationLifecycle(properties, client, executorService);

        assertThatCode(lifecycle::destroy).doesNotThrowAnyException();

        executorService.shutdownNow();
    }

    private static final class FailingDeleteTransport implements RegistrationTransport {

        @Override
        public RegistrationCallResult post(String url, String jsonBody, String authorizationHeader) {
            return RegistrationCallResult.success(200);
        }

        @Override
        public RegistrationCallResult delete(String url, String authorizationHeader) throws IOException {
            throw new IOException("Connection refused");
        }
    }
}
