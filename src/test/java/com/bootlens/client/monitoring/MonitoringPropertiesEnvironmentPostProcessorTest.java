package com.bootlens.client.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringPropertiesEnvironmentPostProcessorTest {

    private final MonitoringPropertiesEnvironmentPostProcessor postProcessor =
        new MonitoringPropertiesEnvironmentPostProcessor();

    @Test
    void mapsLegacyMemoryPressurePropertiesToBootLensNamespace() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("memory.pressure.enabled", "false")
            .withProperty("memory.pressure.warning-threshold-percent", "70")
            .withProperty("memory.pressure.check-interval", "30s");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("bootlens.client.monitoring.memory-pressure.enabled"))
            .isEqualTo("false");
        assertThat(environment.getProperty("bootlens.client.monitoring.memory-pressure.warning-threshold-percent"))
            .isEqualTo("70");
        assertThat(environment.getProperty("bootlens.client.monitoring.memory-pressure.check-interval"))
            .isEqualTo("30s");
    }

    @Test
    void newBootLensNamespaceWinsOverLegacyAlias() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("bootlens.client.monitoring.memory-pressure.enabled", "true")
            .withProperty("memory.pressure.enabled", "false");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("bootlens.client.monitoring.memory-pressure.enabled"))
            .isEqualTo("true");
    }

    @Test
    void mapsEveryLegacyEnabledFlagToBootLensNamespace() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("memory.pressure.enabled", "false")
            .withProperty("file.descriptors.enabled", "false")
            .withProperty("direct.memory.enabled", "false")
            .withProperty("metaspace.enabled", "false")
            .withProperty("gc.pressure.enabled", "false")
            .withProperty("thread.deadlock.enabled", "false")
            .withProperty("log.errors.enabled", "false");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("bootlens.client.monitoring.memory-pressure.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("bootlens.client.monitoring.file-descriptors.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("bootlens.client.monitoring.direct-memory.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("bootlens.client.monitoring.metaspace.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("bootlens.client.monitoring.gc-pressure.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("bootlens.client.monitoring.thread-deadlock.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("bootlens.client.monitoring.log-errors.enabled")).isEqualTo("false");
    }
}
