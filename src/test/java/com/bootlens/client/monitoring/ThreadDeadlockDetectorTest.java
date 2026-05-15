package com.bootlens.client.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThreadDeadlockDetectorTest {

    ScheduledExecutorService executorService;
    ThreadMXBean threadMXBean;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadScheduledExecutor();
        threadMXBean    = mock(ThreadMXBean.class);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    // --- buildSnapshot: no deadlock ---

    @Test
    void buildSnapshotReturnsCleanWhenNoDeadlock() {
        when(threadMXBean.findDeadlockedThreads()).thenReturn(null);
        ThreadDeadlockDetector detector = buildDetector();

        DeadlockSnapshot snapshot = detector.buildSnapshot();

        assertThat(snapshot.deadlocked()).isFalse();
        assertThat(snapshot.threadCount()).isEqualTo(0);
        assertThat(snapshot.threadNames()).isEmpty();
        assertThat(snapshot.checkedAt()).isNotNull();
    }

    @Test
    void buildSnapshotReturnsCleanForEmptyArray() {
        when(threadMXBean.findDeadlockedThreads()).thenReturn(new long[0]);
        ThreadDeadlockDetector detector = buildDetector();

        DeadlockSnapshot snapshot = detector.buildSnapshot();

        assertThat(snapshot.deadlocked()).isFalse();
        assertThat(snapshot.threadCount()).isEqualTo(0);
    }

    // --- lifecycle ---

    @Test
    void lastSnapshotIsNullBeforeFirstCheck() {
        when(threadMXBean.findDeadlockedThreads()).thenReturn(null);
        assertThat(buildDetector().lastSnapshot()).isNull();
    }

    @Test
    void checkPopulatesLastSnapshot() {
        when(threadMXBean.findDeadlockedThreads()).thenReturn(null);
        ThreadDeadlockDetector detector = buildDetector();
        detector.check();
        assertThat(detector.lastSnapshot()).isNotNull();
    }

    @Test
    void checkDoesNotThrow() {
        when(threadMXBean.findDeadlockedThreads()).thenReturn(null);
        ThreadDeadlockDetector detector = buildDetector();
        detector.check();
        detector.check();
        detector.check();
    }

    @Test
    void destroyStopsExecutor() {
        ThreadDeadlockDetector detector = buildDetector();
        detector.destroy();
        assertThat(executorService.isShutdown()).isTrue();
    }

    // --- DeadlockSnapshot factory ---

    @Test
    void cleanSnapshotHasCorrectDefaults() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        DeadlockSnapshot snapshot = DeadlockSnapshot.clean(now);

        assertThat(snapshot.deadlocked()).isFalse();
        assertThat(snapshot.threadCount()).isEqualTo(0);
        assertThat(snapshot.threadNames()).isEmpty();
        assertThat(snapshot.checkedAt()).isEqualTo(now);
    }

    // --- Helpers ---

    private ThreadDeadlockDetector buildDetector() {
        return new ThreadDeadlockDetector(
            new ThreadDeadlockProperties(),
            threadMXBean,
            executorService,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
    }
}
