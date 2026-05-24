package com.bootlens.client.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringPropertiesAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            MemoryPressureAutoConfiguration.class,
            FileDescriptorAutoConfiguration.class,
            DirectMemoryAutoConfiguration.class,
            MetaspaceAutoConfiguration.class,
            GcPressureAutoConfiguration.class,
            ThreadDeadlockAutoConfiguration.class,
            LogErrorRateAutoConfiguration.class));

    @Test
    void monitorAutoConfigurationsCanBeDisabledWithBootLensNamespace() {
        contextRunner
            .withPropertyValues(
                "bootlens.client.monitoring.memory-pressure.enabled=false",
                "bootlens.client.monitoring.file-descriptors.enabled=false",
                "bootlens.client.monitoring.direct-memory.enabled=false",
                "bootlens.client.monitoring.metaspace.enabled=false",
                "bootlens.client.monitoring.gc-pressure.enabled=false",
                "bootlens.client.monitoring.thread-deadlock.enabled=false",
                "bootlens.client.monitoring.log-errors.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(MemoryPressureMonitor.class);
                assertThat(context).doesNotHaveBean(FileDescriptorMonitor.class);
                assertThat(context).doesNotHaveBean(DirectMemoryMonitor.class);
                assertThat(context).doesNotHaveBean(MetaspaceMonitor.class);
                assertThat(context).doesNotHaveBean(GcPressureMonitor.class);
                assertThat(context).doesNotHaveBean(ThreadDeadlockDetector.class);
                assertThat(context).doesNotHaveBean(LogErrorRateMonitor.class);
            });
    }

    @Test
    void memoryPressurePropertiesBindFromBootLensNamespace() {
        contextRunner
            .withConfiguration(AutoConfigurations.of(MemoryPressureAutoConfiguration.class))
            .withPropertyValues(
                "bootlens.client.monitoring.memory-pressure.warning-threshold-percent=66",
                "bootlens.client.monitoring.memory-pressure.check-interval=15s")
            .run(context -> {
                MemoryPressureProperties properties = context.getBean(MemoryPressureProperties.class);
                assertThat(properties.getWarningThresholdPercent()).isEqualTo(66);
                assertThat(properties.getCheckInterval()).hasSeconds(15);
            });
    }
}
