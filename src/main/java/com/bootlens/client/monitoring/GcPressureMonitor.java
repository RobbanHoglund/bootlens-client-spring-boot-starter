package com.bootlens.client.monitoring;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

class GcPressureMonitor implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GcPressureMonitor.class);

    private static final Duration WARNING_RATELIMIT   = Duration.ofMinutes(10);
    private static final Duration CRITICAL_RATELIMIT  = Duration.ofMinutes(5);
    private static final Duration EMERGENCY_RATELIMIT = Duration.ofMinutes(2);

    private final GcPressureProperties properties;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final ScheduledExecutorService executorService;
    private final Clock clock;

    private final AtomicBoolean started   = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicReference<GcSnapshot> lastSnapshot = new AtomicReference<>();

    private volatile long prevCheckMillis  = -1;
    private volatile long prevTotalPauseMs = -1;
    private volatile long prevTotalCount   = -1;

    private volatile Instant lastLoggedAt;
    private volatile MemoryLevel lastLoggedLevel = MemoryLevel.OK;

    GcPressureMonitor(GcPressureProperties properties) {
        this(properties, ManagementFactory.getGarbageCollectorMXBeans(), newDaemonExecutor(), Clock.systemUTC());
    }

    GcPressureMonitor(
        GcPressureProperties properties,
        List<GarbageCollectorMXBean> gcBeans,
        ScheduledExecutorService executorService,
        Clock clock
    ) {
        this.properties = properties;
        this.gcBeans = gcBeans;
        this.executorService = executorService;
        this.clock = clock;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info(
            "GC pressure monitor active: interval={}, thresholds warning={}% critical={}% emergency={}%",
            properties.getCheckInterval(),
            properties.getWarningPausePercent(),
            properties.getCriticalPausePercent(),
            properties.getEmergencyPausePercent()
        );
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
            long nowMillis       = clock.millis();
            long currentPauseMs  = totalPauseMs();
            long currentCount    = totalCount();

            if (prevCheckMillis < 0) {
                prevCheckMillis  = nowMillis;
                prevTotalPauseMs = currentPauseMs;
                prevTotalCount   = currentCount;
                lastSnapshot.set(new GcSnapshot(0, 0, 0.0, currentCount, currentPauseMs, clock.instant()));
                return;
            }

            long intervalMs   = nowMillis - prevCheckMillis;
            long deltaPauseMs = currentPauseMs - prevTotalPauseMs;
            long deltaCount   = currentCount - prevTotalCount;

            prevCheckMillis  = nowMillis;
            prevTotalPauseMs = currentPauseMs;
            prevTotalCount   = currentCount;

            double pausePercent = GcSnapshot.pausePercentOf(deltaPauseMs, intervalMs);
            GcSnapshot snapshot = new GcSnapshot(
                deltaCount, deltaPauseMs, pausePercent, currentCount, currentPauseMs, clock.instant()
            );
            lastSnapshot.set(snapshot);
            evaluateAndLog(snapshot);
        }
        catch (Exception e) {
            log.debug("GC pressure check failed unexpectedly", e);
        }
    }

    MemoryLevel classify(double pausePercent) {
        if (pausePercent >= properties.getEmergencyPausePercent()) return MemoryLevel.EMERGENCY;
        if (pausePercent >= properties.getCriticalPausePercent())  return MemoryLevel.CRITICAL;
        if (pausePercent >= properties.getWarningPausePercent())   return MemoryLevel.WARNING;
        return MemoryLevel.OK;
    }

    GcSnapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    private void evaluateAndLog(GcSnapshot snapshot) {
        MemoryLevel level = classify(snapshot.pausePercent());
        Instant now = clock.instant();

        if (level == MemoryLevel.OK) {
            lastLoggedAt    = null;
            lastLoggedLevel = MemoryLevel.OK;
            return;
        }

        Duration rateLimit     = rateLimitFor(level);
        boolean levelChanged   = level != lastLoggedLevel;
        boolean rateLimitExpired = lastLoggedAt == null || now.isAfter(lastLoggedAt.plus(rateLimit));

        if (levelChanged || rateLimitExpired) {
            lastLoggedAt    = now;
            lastLoggedLevel = level;
            logPressure(level, snapshot);
        }
    }

    private void logPressure(MemoryLevel level, GcSnapshot snapshot) {
        String message = String.format(
            "GC pressure %s: %dms pause in %dms interval (%.1f%%), cumulative %d collections / %dms",
            level,
            snapshot.deltaPauseMs(),
            intervalMs(snapshot),
            snapshot.pausePercent(),
            snapshot.totalCollectionCount(),
            snapshot.totalPauseMs()
        );
        switch (level) {
            case WARNING  -> log.warn(message);
            case CRITICAL, EMERGENCY -> log.error(message);
            default -> { }
        }
    }

    private long intervalMs(GcSnapshot snapshot) {
        return properties.getCheckInterval().toMillis();
    }

    private long totalPauseMs() {
        return gcBeans.stream()
                      .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                      .filter(t -> t >= 0)
                      .sum();
    }

    private long totalCount() {
        return gcBeans.stream()
                      .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                      .filter(c -> c >= 0)
                      .sum();
    }

    private static Duration rateLimitFor(MemoryLevel level) {
        return switch (level) {
            case WARNING   -> WARNING_RATELIMIT;
            case CRITICAL  -> CRITICAL_RATELIMIT;
            case EMERGENCY -> EMERGENCY_RATELIMIT;
            default        -> Duration.ofMinutes(10);
        };
    }

    private static ScheduledExecutorService newDaemonExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "bootlens-gc-pressure");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
