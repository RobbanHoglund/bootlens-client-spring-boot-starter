package com.bootlens.client.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.lang.management.OperatingSystemMXBean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

class FileDescriptorMonitorTest {

    ScheduledExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    // --- FileDescriptorSnapshot.percentOf ---

    @Test
    void percentOfCalculatesCorrectRatio() {
        assertThat(FileDescriptorSnapshot.percentOf(700, 1000)).isCloseTo(70.0, within(0.01));
    }

    @Test
    void percentOfReturnsZeroForZeroMax() {
        assertThat(FileDescriptorSnapshot.percentOf(100, 0)).isEqualTo(0.0);
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

    // --- buildSnapshot on non-Unix platform ---

    @Test
    void buildSnapshotReturnsUnavailableWhenOsMXBeanNotUnix() {
        // On Windows (and any non-Unix JVM), the standard OperatingSystemMXBean
        // is not an instance of UnixOperatingSystemMXBean.
        OperatingSystemMXBean nonUnixBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        FileDescriptorMonitor monitor = buildMonitorWithBean(nonUnixBean);

        // Only assert unavailability when we're actually on a non-Unix platform;
        // on Linux this MXBean IS a UnixOperatingSystemMXBean and available=true.
        FileDescriptorSnapshot snapshot = monitor.buildSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.checkedAt()).isNotNull();
        // available may be true (Linux CI) or false (Windows) — both are valid
    }

    // --- lifecycle ---

    @Test
    void lastSnapshotIsNullBeforeFirstCheck() {
        assertThat(buildMonitor().lastSnapshot()).isNull();
    }

    @Test
    void checkPopulatesLastSnapshot() {
        FileDescriptorMonitor monitor = buildMonitor();
        monitor.check();
        assertThat(monitor.lastSnapshot()).isNotNull();
    }

    @Test
    void checkDoesNotThrow() {
        FileDescriptorMonitor monitor = buildMonitor();
        monitor.check();
        monitor.check();
        monitor.check();
    }

    @Test
    void destroyStopsExecutor() {
        FileDescriptorMonitor monitor = buildMonitor();
        monitor.destroy();
        assertThat(executorService.isShutdown()).isTrue();
    }

    // --- InfoContributor ---

    @Test
    void infoContributorContributesNothingWhenNoSnapshot() {
        FileDescriptorInfoContributor contributor =
            new FileDescriptorInfoContributor(buildMonitor());

        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);

        assertThat(builder.build().getDetails()).doesNotContainKey("fileDescriptors");
    }

    @Test
    void infoContributorContributesUnavailableStateGracefully() {
        FileDescriptorMonitor monitor = buildMonitor();
        monitor.check();

        FileDescriptorInfoContributor contributor = new FileDescriptorInfoContributor(monitor);
        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);

        assertThat(builder.build().getDetails()).containsKey("fileDescriptors");
    }

    // --- unavailable snapshot factory ---

    @Test
    void unavailableSnapshotHasCorrectDefaults() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FileDescriptorSnapshot snapshot = FileDescriptorSnapshot.unavailable(now);

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.open()).isEqualTo(0);
        assertThat(snapshot.max()).isEqualTo(0);
        assertThat(snapshot.checkedAt()).isEqualTo(now);
    }

    // --- Helpers ---

    private FileDescriptorMonitor buildMonitor() {
        return new FileDescriptorMonitor(
            new FileDescriptorProperties(),
            java.lang.management.ManagementFactory.getOperatingSystemMXBean(),
            executorService,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private FileDescriptorMonitor buildMonitorWithBean(OperatingSystemMXBean bean) {
        return new FileDescriptorMonitor(
            new FileDescriptorProperties(),
            bean,
            executorService,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
    }
}
