package com.bootlens.client.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetaspaceMonitorTest {

    ScheduledExecutorService executorService;
    MemoryPoolMXBean metaspacePool;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadScheduledExecutor();
        metaspacePool   = mock(MemoryPoolMXBean.class);
        when(metaspacePool.getName()).thenReturn("Metaspace");
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    // --- MetaspaceSnapshot.percentOf ---

    @Test
    void percentOfCalculatesCorrectRatio() {
        assertThat(MetaspaceSnapshot.percentOf(70 * 1024 * 1024L, 100 * 1024 * 1024L))
            .isCloseTo(70.0, within(0.01));
    }

    @Test
    void percentOfReturnsZeroForZeroMax() {
        assertThat(MetaspaceSnapshot.percentOf(100, 0)).isEqualTo(0.0);
    }

    // --- classify ---

    @Test
    void classifyReturnsOkBelowWarning() {
        assertThat(buildMonitor().classify(69.9)).isEqualTo(MemoryLevel.OK);
    }

    @Test
    void classifyReturnsWarningAtThreshold() {
        assertThat(buildMonitor().classify(70.0)).isEqualTo(MemoryLevel.WARNING);
    }

    @Test
    void classifyReturnsCriticalAtThreshold() {
        assertThat(buildMonitor().classify(85.0)).isEqualTo(MemoryLevel.CRITICAL);
    }

    @Test
    void classifyReturnsEmergencyAtThreshold() {
        assertThat(buildMonitor().classify(95.0)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    // --- buildSnapshot with bounded max ---

    @Test
    void buildSnapshotComputesPercentWhenMaxIsSet() {
        long used  = 70 * 1024 * 1024L;
        long max   = 100 * 1024 * 1024L;
        when(metaspacePool.getUsage()).thenReturn(new MemoryUsage(-1, used, used, max));

        MetaspaceSnapshot snapshot = buildMonitor().buildSnapshot();

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.bounded()).isTrue();
        assertThat(snapshot.usedBytes()).isEqualTo(used);
        assertThat(snapshot.maxBytes()).isEqualTo(max);
        assertThat(snapshot.percent()).isCloseTo(70.0, within(0.01));
    }

    @Test
    void buildSnapshotMarksBoundedFalseWhenMaxIsMinusOne() {
        long used = 70 * 1024 * 1024L;
        when(metaspacePool.getUsage()).thenReturn(new MemoryUsage(-1, used, used, -1));

        MetaspaceSnapshot snapshot = buildMonitor().buildSnapshot();

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.bounded()).isFalse();
        assertThat(snapshot.percent()).isEqualTo(0.0);
    }

    @Test
    void buildSnapshotReturnsUnavailableWhenPoolNotFound() {
        MetaspaceMonitor monitor = new MetaspaceMonitor(
            new MetaspaceProperties(), List.of(), executorService, fixedClock()
        );

        MetaspaceSnapshot snapshot = monitor.buildSnapshot();

        assertThat(snapshot.available()).isFalse();
    }

    // --- lifecycle ---

    @Test
    void lastSnapshotIsNullBeforeFirstCheck() {
        assertThat(buildMonitor().lastSnapshot()).isNull();
    }

    @Test
    void checkPopulatesLastSnapshot() {
        when(metaspacePool.getUsage()).thenReturn(new MemoryUsage(-1, 50 * 1024 * 1024L, 50 * 1024 * 1024L, -1));
        MetaspaceMonitor monitor = buildMonitor();
        monitor.check();
        assertThat(monitor.lastSnapshot()).isNotNull();
    }

    @Test
    void destroyStopsExecutor() {
        MetaspaceMonitor monitor = buildMonitor();
        monitor.destroy();
        assertThat(executorService.isShutdown()).isTrue();
    }

    // --- real JVM integration ---

    @Test
    void buildSnapshotWithRealJvmReturnsNonNull() {
        MetaspaceMonitor monitor = new MetaspaceMonitor(
            new MetaspaceProperties(),
            ManagementFactory.getMemoryPoolMXBeans(),
            executorService,
            fixedClock()
        );

        MetaspaceSnapshot snapshot = monitor.buildSnapshot();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.checkedAt()).isNotNull();
        // Metaspace is available on all modern JVMs
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.usedBytes()).isGreaterThan(0);
        assertThat(snapshot.committedBytes()).isGreaterThan(0);
    }

    // --- unavailable snapshot factory ---

    @Test
    void unavailableSnapshotHasCorrectDefaults() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        MetaspaceSnapshot snapshot = MetaspaceSnapshot.unavailable(now);

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.bounded()).isFalse();
        assertThat(snapshot.usedBytes()).isEqualTo(0);
        assertThat(snapshot.maxBytes()).isEqualTo(-1);
        assertThat(snapshot.checkedAt()).isEqualTo(now);
    }

    // --- Helpers ---

    private MetaspaceMonitor buildMonitor() {
        return new MetaspaceMonitor(
            new MetaspaceProperties(),
            List.of(metaspacePool),
            executorService,
            fixedClock()
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
