package com.bootlens.client.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class BootLensMonitorHealthIndicatorTest {

    // --- Status mapping ---

    @Test
    void returnsUpWhenAllMonitorsAreOk() {
        BootLensMonitorHealthIndicator indicator = new BootLensMonitorHealthIndicator(List.of(
            source("memoryPressure", MemoryLevel.OK),
            source("gcPressure",     MemoryLevel.OK)
        ));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void returnsUpWhenWorstLevelIsWarning() {
        BootLensMonitorHealthIndicator indicator = new BootLensMonitorHealthIndicator(List.of(
            source("memoryPressure", MemoryLevel.WARNING),
            source("gcPressure",     MemoryLevel.OK)
        ));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void returnsOutOfServiceWhenWorstLevelIsCritical() {
        BootLensMonitorHealthIndicator indicator = new BootLensMonitorHealthIndicator(List.of(
            source("memoryPressure", MemoryLevel.OK),
            source("gcPressure",     MemoryLevel.CRITICAL)
        ));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void returnsDownWhenWorstLevelIsEmergency() {
        BootLensMonitorHealthIndicator indicator = new BootLensMonitorHealthIndicator(List.of(
            source("memoryPressure", MemoryLevel.CRITICAL),
            source("threadDeadlock", MemoryLevel.EMERGENCY)
        ));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    // --- Worst level wins ---

    @Test
    void emergencyAlwaysBeatsLowerLevels() {
        BootLensMonitorHealthIndicator indicator = new BootLensMonitorHealthIndicator(List.of(
            source("a", MemoryLevel.WARNING),
            source("b", MemoryLevel.CRITICAL),
            source("c", MemoryLevel.EMERGENCY),
            source("d", MemoryLevel.OK)
        ));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    // --- Details ---

    @Test
    void detailsContainEachMonitorLevel() {
        BootLensMonitorHealthIndicator indicator = new BootLensMonitorHealthIndicator(List.of(
            source("memoryPressure", MemoryLevel.CRITICAL),
            source("gcPressure",     MemoryLevel.OK)
        ));

        Health health = indicator.health();
        assertThat(health.getDetails()).containsEntry("memoryPressure", "CRITICAL");
        assertThat(health.getDetails()).containsEntry("gcPressure",     "OK");
    }

    @Test
    void detailsContainWorstLevel() {
        BootLensMonitorHealthIndicator indicator = new BootLensMonitorHealthIndicator(List.of(
            source("memoryPressure", MemoryLevel.WARNING),
            source("gcPressure",     MemoryLevel.CRITICAL)
        ));

        assertThat(indicator.health().getDetails()).containsEntry("worstLevel", "CRITICAL");
    }

    // --- Empty source list ---

    @Test
    void returnsUpWithNoSources() {
        BootLensMonitorHealthIndicator indicator = new BootLensMonitorHealthIndicator(List.of());

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("worstLevel", "OK");
    }

    // --- Helpers ---

    private static MonitorLevelSource source(String name, MemoryLevel level) {
        return new MonitorLevelSource() {
            public String name() { return name; }
            public MemoryLevel currentLevel() { return level; }
        };
    }
}
