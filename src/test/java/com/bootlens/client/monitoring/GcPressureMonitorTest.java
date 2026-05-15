package com.bootlens.client.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.GarbageCollectorMXBean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GcPressureMonitorTest {

    ScheduledExecutorService executorService;
    GarbageCollectorMXBean youngGen;
    GarbageCollectorMXBean oldGen;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadScheduledExecutor();
        youngGen = mock(GarbageCollectorMXBean.class);
        oldGen   = mock(GarbageCollectorMXBean.class);
        mockGcBeans(youngGen, 0, 0);
        mockGcBeans(oldGen, 0, 0);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    // --- GcSnapshot.pausePercentOf ---

    @Test
    void pausePercentOfCalculatesCorrectRatio() {
        assertThat(GcSnapshot.pausePercentOf(3_000, 60_000)).isCloseTo(5.0, within(0.01));
    }

    @Test
    void pausePercentOfReturnsZeroForZeroInterval() {
        assertThat(GcSnapshot.pausePercentOf(1_000, 0)).isEqualTo(0.0);
    }

    // --- classify ---

    @Test
    void classifyReturnsOkBelowWarning() {
        assertThat(buildMonitor().classify(4.9)).isEqualTo(MemoryLevel.OK);
    }

    @Test
    void classifyReturnsWarningAtThreshold() {
        assertThat(buildMonitor().classify(5.0)).isEqualTo(MemoryLevel.WARNING);
    }

    @Test
    void classifyReturnsCriticalAtThreshold() {
        assertThat(buildMonitor().classify(20.0)).isEqualTo(MemoryLevel.CRITICAL);
    }

    @Test
    void classifyReturnsEmergencyAtThreshold() {
        assertThat(buildMonitor().classify(40.0)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    @Test
    void customThresholdsAreRespected() {
        GcPressureProperties props = new GcPressureProperties();
        props.setWarningPausePercent(2);
        props.setCriticalPausePercent(10);
        props.setEmergencyPausePercent(30);
        GcPressureMonitor monitor = new GcPressureMonitor(props, List.of(youngGen), executorService, fixedClock());

        assertThat(monitor.classify(1.9)).isEqualTo(MemoryLevel.OK);
        assertThat(monitor.classify(2.0)).isEqualTo(MemoryLevel.WARNING);
        assertThat(monitor.classify(10.0)).isEqualTo(MemoryLevel.CRITICAL);
        assertThat(monitor.classify(30.0)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    // --- check() baseline and delta logic ---

    @Test
    void firstCheckEstablishesBaselineWithoutSnapshot() {
        GcPressureMonitor monitor = buildMonitor();

        monitor.check();

        // First check sets baseline but produces a zero snapshot
        GcSnapshot snapshot = monitor.lastSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.deltaCollectionCount()).isEqualTo(0);
        assertThat(snapshot.deltaPauseMs()).isEqualTo(0);
    }

    @Test
    void secondCheckMeasuresDeltaFromBaseline() {
        GcPressureMonitor monitor = buildMonitorWithClock(new MutableClock(0));

        // First check: baseline at 0ms, GC at 0ms total
        mockGcBeans(youngGen, 0, 0);
        monitor.check();

        // Second check: 60 000ms later, GC has accumulated 3 000ms
        mockGcBeans(youngGen, 50, 3_000);
        monitor.check();

        GcSnapshot snapshot = monitor.lastSnapshot();
        assertThat(snapshot.deltaCollectionCount()).isEqualTo(50);
        assertThat(snapshot.deltaPauseMs()).isEqualTo(3_000);
        assertThat(snapshot.pausePercent()).isCloseTo(5.0, within(0.1));
    }

    @Test
    void lastSnapshotIsNullBeforeFirstCheck() {
        assertThat(buildMonitor().lastSnapshot()).isNull();
    }

    @Test
    void destroyStopsExecutor() {
        GcPressureMonitor monitor = buildMonitor();
        monitor.destroy();
        assertThat(executorService.isShutdown()).isTrue();
    }

    @Test
    void negativeGcTimeIsIgnored() {
        mockGcBeans(youngGen, -1, -1);
        GcPressureMonitor monitor = buildMonitor();

        monitor.check();

        GcSnapshot snapshot = monitor.lastSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.totalPauseMs()).isEqualTo(0);
    }

    // --- Helpers ---

    private GcPressureMonitor buildMonitor() {
        return new GcPressureMonitor(new GcPressureProperties(), List.of(youngGen, oldGen), executorService, fixedClock());
    }

    private GcPressureMonitor buildMonitorWithClock(Clock clock) {
        return new GcPressureMonitor(new GcPressureProperties(), List.of(youngGen), executorService, clock);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    private static void mockGcBeans(GarbageCollectorMXBean bean, long count, long timeMs) {
        when(bean.getCollectionCount()).thenReturn(count);
        when(bean.getCollectionTime()).thenReturn(timeMs);
    }

    /**
     * A clock whose millis value advances by checkInterval on each call after the first,
     * simulating two consecutive check ticks with a realistic interval.
     */
    private static final class MutableClock extends Clock {

        private long millis;
        private int callCount = 0;

        MutableClock(long initialMillis) {
            this.millis = initialMillis;
        }

        @Override
        public long millis() {
            if (callCount++ > 0) {
                millis += 60_000;
            }
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
