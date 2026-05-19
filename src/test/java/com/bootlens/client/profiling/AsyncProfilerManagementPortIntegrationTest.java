package com.bootlens.client.profiling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,bootlensProfiler"
    }
)
class AsyncProfilerManagementPortIntegrationTest {

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void profilerEndpointIsServedFromManagementPortOnly() {
        assertThat(managementPort).isNotEqualTo(serverPort);

        ResponseEntity<String> managementResponse = restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/bootlensProfiler",
            String.class
        );

        assertThat(managementResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(managementResponse.getBody()).contains("\"profilerAvailable\"");

        try {
            restTemplate.getForEntity(
                "http://localhost:" + serverPort + "/actuator/bootlensProfiler",
                String.class
            );
        }
        catch (HttpClientErrorException.NotFound ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(404);
            return;
        }

        throw new AssertionError("Expected profiler endpoint to be absent from the application port");
    }

    @SpringBootApplication
    static class TestApplication {
    }
}
