package com.bootlens.client.monitoring;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

class ThreadDeadlockDetector implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ThreadDeadlockDetector.class);

    private static final Duration DEADLOCK_RATELIMIT = Duration.ofMinutes(2);

    private final ThreadDeadlockProperties properties;
    private final ThreadMXBean threadMXBean;
    private final ScheduledExecutorService executorService;
    private final Clock clock;

    private final AtomicBoolean started   = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicReference<DeadlockSnapshot> lastSnapshot = new AtomicReference<>();

    private volatile Instant lastLoggedAt;
    private volatile boolean wasDeadlocked = false;

    ThreadDeadlockDetector(ThreadDeadlockProperties properties) {
        this(properties, ManagementFactory.getThreadMXBean(), newDaemonExecutor(), Clock.systemUTC());
    }

    ThreadDeadlockDetector(
        ThreadDeadlockProperties properties,
        ThreadMXBean threadMXBean,
        ScheduledExecutorService executorService,
        Clock clock
    ) {
        this.properties    = properties;
        this.threadMXBean  = threadMXBean;
        this.executorService = executorService;
        this.clock         = clock;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("Thread deadlock detector active: interval={}", properties.getCheckInterval());
        long intervalMillis = Math.max(5_000L, properties.getCheckInterval().toMillis());
        executorService.scheduleAtFixedRate(this::check, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            executorService.shutdownNow();
        }
    }

    void check() {
        if (destroyed.get()) {
            return;
        }
        try {
            DeadlockSnapshot snapshot = buildSnapshot();
            lastSnapshot.set(snapshot);
            evaluateAndLog(snapshot);
        }
        catch (Exception e) {
            log.debug("Deadlock check failed unexpectedly", e);
        }
    }

    DeadlockSnapshot buildSnapshot() {
        long[] ids = threadMXBean.findDeadlockedThreads();
        if (ids == null || ids.length == 0) {
            return DeadlockSnapshot.clean(clock.instant());
        }
        ThreadInfo[] infos = threadMXBean.getThreadInfo(ids, true, true);
        List<String> names = Arrays.stream(infos)
            .filter(info -> info != null)
            .map(info -> info.getThreadName() + " (id=" + info.getThreadId() + ")")
            .toList();
        return new DeadlockSnapshot(true, names.size(), names, clock.instant());
    }

    DeadlockSnapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    private void evaluateAndLog(DeadlockSnapshot snapshot) {
        if (!snapshot.deadlocked()) {
            if (wasDeadlocked) {
                log.info("Thread deadlock resolved — no deadlocked threads detected");
            }
            wasDeadlocked = false;
            lastLoggedAt  = null;
            return;
        }

        Instant now              = clock.instant();
        boolean firstDetection   = !wasDeadlocked;
        boolean rateLimitExpired = lastLoggedAt == null || now.isAfter(lastLoggedAt.plus(DEADLOCK_RATELIMIT));

        wasDeadlocked = true;

        if (firstDetection || rateLimitExpired) {
            lastLoggedAt = now;
            log.error(
                "DEADLOCK DETECTED: {} thread(s) deadlocked — {}",
                snapshot.threadCount(),
                String.join(", ", snapshot.threadNames())
            );
        }
    }

    private static ScheduledExecutorService newDaemonExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "bootlens-deadlock-detector");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
