package com.bootlens.client.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryPressureMonitorTest {

    @TempDir
    Path tempDir;

    ScheduledExecutorService executorService;
    MemoryMXBean memoryMXBean;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadScheduledExecutor();
        memoryMXBean = mock(MemoryMXBean.class);
        mockHeap(512, 1024);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    // --- MemorySnapshot.percentOf ---

    @Test
    void percentOfReturnsCorrectRatio() {
        assertThat(MemorySnapshot.percentOf(512, 1024)).isCloseTo(50.0, within(0.01));
    }

    @Test
    void percentOfReturnsZeroForZeroMax() {
        assertThat(MemorySnapshot.percentOf(100, 0)).isEqualTo(0.0);
    }

    @Test
    void percentOfReturnsZeroForNegativeMax() {
        assertThat(MemorySnapshot.percentOf(100, -1)).isEqualTo(0.0);
    }

    // --- classify() thresholds ---

    @Test
    void classifyReturnsOkBelowWarning() {
        MemoryPressureMonitor monitor = buildMonitor(defaultProperties());
        assertThat(monitor.classify(74.9)).isEqualTo(MemoryLevel.OK);
    }

    @Test
    void classifyReturnsWarningAtThreshold() {
        MemoryPressureMonitor monitor = buildMonitor(defaultProperties());
        assertThat(monitor.classify(75.0)).isEqualTo(MemoryLevel.WARNING);
    }

    @Test
    void classifyReturnsCriticalAtThreshold() {
        MemoryPressureMonitor monitor = buildMonitor(defaultProperties());
        assertThat(monitor.classify(85.0)).isEqualTo(MemoryLevel.CRITICAL);
    }

    @Test
    void classifyReturnsEmergencyAtThreshold() {
        MemoryPressureMonitor monitor = buildMonitor(defaultProperties());
        assertThat(monitor.classify(92.0)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    @Test
    void classifyReturnsEmergencyAboveThreshold() {
        MemoryPressureMonitor monitor = buildMonitor(defaultProperties());
        assertThat(monitor.classify(99.9)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    @Test
    void customThresholdsAreRespected() {
        MemoryPressureProperties props = defaultProperties();
        props.setWarningThresholdPercent(50);
        props.setCriticalThresholdPercent(70);
        props.setEmergencyThresholdPercent(90);
        MemoryPressureMonitor monitor = buildMonitor(props);
        assertThat(monitor.classify(49.9)).isEqualTo(MemoryLevel.OK);
        assertThat(monitor.classify(50.0)).isEqualTo(MemoryLevel.WARNING);
        assertThat(monitor.classify(70.0)).isEqualTo(MemoryLevel.CRITICAL);
        assertThat(monitor.classify(90.0)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    // --- buildSnapshot() with cgroup files ---

    @Test
    void buildSnapshotReadsHeapFromMXBean() throws IOException {
        mockHeap(768, 1024);
        MemoryPressureMonitor monitor = buildMonitorNoCgroup();

        MemorySnapshot snapshot = monitor.buildSnapshot();

        assertThat(snapshot.heapUsed()).isEqualTo(768L * 1024 * 1024);
        assertThat(snapshot.heapMax()).isEqualTo(1024L * 1024 * 1024);
        assertThat(snapshot.heapPercent()).isCloseTo(75.0, within(0.01));
    }

    @Test
    void buildSnapshotReadsCgroupFiles() throws IOException {
        long usedBytes = 200L * 1024 * 1024;
        long maxBytes = 512L * 1024 * 1024;
        Path cgroupCurrent = writeCgroupFile("memory.current", String.valueOf(usedBytes));
        Path cgroupMax = writeCgroupFile("memory.max", String.valueOf(maxBytes));
        MemoryPressureMonitor monitor = buildMonitorWithCgroup(cgroupCurrent, cgroupMax);

        MemorySnapshot snapshot = monitor.buildSnapshot();

        assertThat(snapshot.containerUsed()).isEqualTo(usedBytes);
        assertThat(snapshot.containerMax()).isEqualTo(maxBytes);
        assertThat(snapshot.containerPercent()).isCloseTo(39.06, within(0.1));
        assertThat(snapshot.hasContainerMetrics()).isTrue();
    }

    @Test
    void buildSnapshotTreatsMaxValueAsUnlimited() throws IOException {
        Path cgroupCurrent = writeCgroupFile("memory.current", "104857600");
        Path cgroupMax = writeCgroupFile("memory.max", "max");
        MemoryPressureMonitor monitor = buildMonitorWithCgroup(cgroupCurrent, cgroupMax);

        MemorySnapshot snapshot = monitor.buildSnapshot();

        assertThat(snapshot.containerMax()).isNull();
        assertThat(snapshot.containerPercent()).isNull();
        assertThat(snapshot.containerUsed()).isEqualTo(104857600L);
    }

    @Test
    void buildSnapshotContinuesGracefullyWhenCgroupFilesAbsent() {
        MemoryPressureMonitor monitor = buildMonitorWithCgroup(
            Path.of("/nonexistent/memory.current"),
            Path.of("/nonexistent/memory.max")
        );

        MemorySnapshot snapshot = monitor.buildSnapshot();

        assertThat(snapshot.heapUsed()).isPositive();
        assertThat(snapshot.containerUsed()).isNull();
        assertThat(snapshot.containerMax()).isNull();
        assertThat(snapshot.containerPercent()).isNull();
    }

    @Test
    void cgroupIsNotRetriedAfterFirstFailure() {
        MemoryPressureMonitor monitor = buildMonitorWithCgroup(
            Path.of("/nonexistent/memory.current"),
            Path.of("/nonexistent/memory.max")
        );

        monitor.buildSnapshot();
        monitor.buildSnapshot();

        // No exception thrown on second call — cgroup reads are suppressed
        MemorySnapshot snapshot = monitor.buildSnapshot();
        assertThat(snapshot.containerUsed()).isNull();
    }

    // --- Rate limiting ---

    @Test
    void firstCheckPopulatesLastSnapshot() throws IOException {
        mockHeap(900, 1024);
        MemoryPressureMonitor monitor = buildMonitorNoCgroup();

        monitor.check();

        assertThat(monitor.lastSnapshot()).isNotNull();
        assertThat(monitor.lastSnapshot().heapUsed()).isEqualTo(900L * 1024 * 1024);
    }

    @Test
    void checkDoesNotThrowWhenCgroupFilesAbsent() {
        MemoryPressureMonitor monitor = buildMonitorWithCgroup(
            Path.of("/nonexistent/memory.current"),
            Path.of("/nonexistent/memory.max")
        );

        // Should complete without exception
        monitor.check();
        monitor.check();

        assertThat(monitor.lastSnapshot()).isNotNull();
    }

    @Test
    void lastSnapshotIsNullBeforeFirstCheck() {
        MemoryPressureMonitor monitor = buildMonitorNoCgroup();
        assertThat(monitor.lastSnapshot()).isNull();
    }

    @Test
    void destroyStopsExecutor() {
        MemoryPressureMonitor monitor = buildMonitorNoCgroup();
        monitor.destroy();
        assertThat(executorService.isShutdown()).isTrue();
    }

    // --- Helpers ---

    private void mockHeap(long usedMb, long maxMb) {
        MemoryUsage usage = new MemoryUsage(0, usedMb * 1024 * 1024, maxMb * 1024 * 1024, maxMb * 1024 * 1024);
        when(memoryMXBean.getHeapMemoryUsage()).thenReturn(usage);
    }

    private MemoryPressureMonitor buildMonitor(MemoryPressureProperties props) {
        return new MemoryPressureMonitor(
            props,
            memoryMXBean,
            executorService,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
            "/nonexistent/memory.current",
            "/nonexistent/memory.max"
        );
    }

    private MemoryPressureMonitor buildMonitorNoCgroup() {
        return buildMonitorWithCgroup(
            Path.of("/nonexistent/memory.current"),
            Path.of("/nonexistent/memory.max")
        );
    }

    private MemoryPressureMonitor buildMonitorWithCgroup(Path current, Path max) {
        return new MemoryPressureMonitor(
            defaultProperties(),
            memoryMXBean,
            executorService,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
            current.toString(),
            max.toString()
        );
    }

    private Path writeCgroupFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content + "\n");
        return file;
    }

    private static MemoryPressureProperties defaultProperties() {
        return new MemoryPressureProperties();
    }
}
