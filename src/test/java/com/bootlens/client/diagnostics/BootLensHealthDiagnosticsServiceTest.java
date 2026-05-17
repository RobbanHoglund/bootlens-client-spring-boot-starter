package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;

class BootLensHealthDiagnosticsServiceTest {

    @Test
    void capturesContributorTimingsAndGroupsNestedContributors() {
        BootLensDiagnosticsProperties properties = new BootLensDiagnosticsProperties();
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        registry.registerContributor("db", slowIndicator(520));
        registry.registerContributor("readiness", CompositeHealthContributor.fromMap(java.util.Map.of(
            "ping", (HealthIndicator) () -> Health.up().withDetail("ok", true).build()
        )));

        BootLensHealthDiagnosticsService service = new BootLensHealthDiagnosticsService(
            properties,
            registry,
            emptyProvider());

        BootLensHealthDiagnosticsService.HealthLatencyDiagnostics diagnostics = service.capture();

        assertThat(diagnostics.status()).isEqualTo("UP");
        assertThat(diagnostics.totalDurationMs()).isNotNull();
        assertThat(diagnostics.contributors()).extracting(BootLensHealthDiagnosticsService.HealthContributorTiming::path)
            .contains("db", "readiness/ping");
        assertThat(diagnostics.contributors()).anySatisfy(contributor -> {
            if ("db".equals(contributor.path())) {
                assertThat(contributor.slow()).isTrue();
                assertThat(contributor.status()).isEqualTo("UP");
            }
        });
    }

    private static HealthIndicator slowIndicator(long sleepMs) {
        return () -> {
            try {
                Thread.sleep(sleepMs);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return Health.up().withDetail("database", "ok").build();
        };
    }

    private static ObjectProvider<org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups getIfAvailable() {
                return null;
            }

            @Override
            public org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups getIfUnique() {
                return null;
            }

            @Override
            public org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Iterator<org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups> iterator() {
                return java.util.Collections.emptyIterator();
            }
        };
    }
}
