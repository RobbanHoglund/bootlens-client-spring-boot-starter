package com.bootlens.client.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectMemoryMonitorTest {

    ScheduledExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    // --- DirectMemorySnapshot.percentOf ---

    @Test
    void percentOfCalculatesCorrectRatio() {
        assertThat(DirectMemorySnapshot.percentOf(128 * 1024 * 1024L, 256 * 1024 * 1024L))
            .isCloseTo(50.0, within(0.01));
    }

    @Test
    void percentOfReturnsZeroForZeroMax() {
        assertThat(DirectMemorySnapshot.percentOf(100, 0)).isEqualTo(0.0);
    }

    // --- classify ---

    @Test
    void classifyReturnsOkBelowWarning() {
        assertThat(buildMonitor().classify(49.9)).isEqualTo(MemoryLevel.OK);
    }

    @Test
    void classifyReturnsWarningAtThreshold() {
        assertThat(buildMonitor().classify(50.0)).isEqualTo(MemoryLevel.WARNING);
    }

    @Test
    void classifyReturnsCriticalAtThreshold() {
        assertThat(buildMonitor().classify(75.0)).isEqualTo(MemoryLevel.CRITICAL);
    }

    @Test
    void classifyReturnsEmergencyAtThreshold() {
        assertThat(buildMonitor().classify(90.0)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    @Test
    void customThresholdsAreRespected() {
        DirectMemoryProperties props = new DirectMemoryProperties();
        props.setWarningThresholdPercent(30);
        props.setCriticalThresholdPercent(60);
        props.setEmergencyThresholdPercent(80);
        DirectMemoryMonitor monitor = new DirectMemoryMonitor(props, executorService, fixedClock());

        assertThat(monitor.classify(29.9)).isEqualTo(MemoryLevel.OK);
        assertThat(monitor.classify(30.0)).isEqualTo(MemoryLevel.WARNING);
        assertThat(monitor.classify(60.0)).isEqualTo(MemoryLevel.CRITICAL);
        assertThat(monitor.classify(80.0)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    // --- lifecycle ---

    @Test
    void lastSnapshotIsNullBeforeFirstCheck() {
        assertThat(buildMonitor().lastSnapshot()).isNull();
    }

    @Test
    void checkPopulatesLastSnapshot() {
        DirectMemoryMonitor monitor = buildMonitor();
        monitor.check();
        assertThat(monitor.lastSnapshot()).isNotNull();
    }

    @Test
    void checkDoesNotThrow() {
        DirectMemoryMonitor monitor = buildMonitor();
        monitor.check();
        monitor.check();
        monitor.check();
    }

    @Test
    void destroyStopsExecutor() {
        DirectMemoryMonitor monitor = buildMonitor();
        monitor.destroy();
        assertThat(executorService.isShutdown()).isTrue();
    }

    @Test
    void buildSnapshotReturnsNonNullSnapshot() {
        DirectMemorySnapshot snapshot = buildMonitor().buildSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.checkedAt()).isNotNull();
        assertThat(snapshot.usedBytes()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.bufferCount()).isGreaterThanOrEqualTo(0);
    }

    // --- unavailable snapshot factory ---

    @Test
    void unavailableSnapshotHasCorrectDefaults() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        DirectMemorySnapshot snapshot = DirectMemorySnapshot.unavailable(now);

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.bufferCount()).isEqualTo(0);
        assertThat(snapshot.usedBytes()).isEqualTo(0);
        assertThat(snapshot.maxBytes()).isEqualTo(-1);
        assertThat(snapshot.checkedAt()).isEqualTo(now);
    }

    // --- Helpers ---

    private DirectMemoryMonitor buildMonitor() {
        return new DirectMemoryMonitor(new DirectMemoryProperties(), executorService, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
