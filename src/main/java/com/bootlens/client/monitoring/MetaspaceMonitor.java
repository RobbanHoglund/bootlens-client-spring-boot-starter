package com.bootlens.client.monitoring;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
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

class MetaspaceMonitor implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MetaspaceMonitor.class);

    private static final Duration WARNING_RATELIMIT   = Duration.ofMinutes(10);
    private static final Duration CRITICAL_RATELIMIT  = Duration.ofMinutes(5);
    private static final Duration EMERGENCY_RATELIMIT = Duration.ofMinutes(2);

    private final MetaspaceProperties properties;
    private final List<MemoryPoolMXBean> memoryPools;
    private final ScheduledExecutorService executorService;
    private final Clock clock;

    private final AtomicBoolean started   = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicReference<MetaspaceSnapshot> lastSnapshot = new AtomicReference<>();

    private volatile Instant lastLoggedAt;
    private volatile MemoryLevel lastLoggedLevel = MemoryLevel.OK;

    MetaspaceMonitor(MetaspaceProperties properties) {
        this(properties, ManagementFactory.getMemoryPoolMXBeans(), newDaemonExecutor(), Clock.systemUTC());
    }

    MetaspaceMonitor(
        MetaspaceProperties properties,
        List<MemoryPoolMXBean> memoryPools,
        ScheduledExecutorService executorService,
        Clock clock
    ) {
        this.properties    = properties;
        this.memoryPools   = memoryPools;
        this.executorService = executorService;
        this.clock         = clock;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        MetaspaceSnapshot probe = buildSnapshot();
        if (!probe.available()) {
            log.debug("Metaspace memory pool not found — metaspace monitor will not produce snapshots");
        } else if (!probe.bounded()) {
            log.info(
                "Metaspace monitor active: interval={}, no max configured (thresholds inactive — reporting used/committed only)",
                properties.getCheckInterval()
            );
        } else {
            log.info(
                "Metaspace monitor active: interval={}, thresholds warning={}% critical={}% emergency={}%, max={}MB",
                properties.getCheckInterval(),
                properties.getWarningThresholdPercent(),
                properties.getCriticalThresholdPercent(),
                properties.getEmergencyThresholdPercent(),
                probe.maxBytes() / (1024 * 1024)
            );
        }
        lastSnapshot.set(probe);
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
            MetaspaceSnapshot snapshot = buildSnapshot();
            lastSnapshot.set(snapshot);
            if (snapshot.available() && snapshot.bounded()) {
                evaluateAndLog(snapshot);
            }
        }
        catch (Exception e) {
            log.debug("Metaspace check failed unexpectedly", e);
        }
    }

    MetaspaceSnapshot buildSnapshot() {
        MemoryPoolMXBean pool = memoryPools.stream()
            .filter(p -> "Metaspace".equals(p.getName()))
            .findFirst()
            .orElse(null);

        if (pool == null) {
            return MetaspaceSnapshot.unavailable(clock.instant());
        }

        MemoryUsage usage     = pool.getUsage();
        long used             = usage.getUsed();
        long committed        = usage.getCommitted();
        long max              = usage.getMax();
        boolean bounded       = max > 0;
        double percent        = bounded ? MetaspaceSnapshot.percentOf(used, max) : 0.0;

        return new MetaspaceSnapshot(used, committed, max, percent, bounded, true, clock.instant());
    }

    MemoryLevel classify(double percent) {
        if (percent >= properties.getEmergencyThresholdPercent()) return MemoryLevel.EMERGENCY;
        if (percent >= properties.getCriticalThresholdPercent())  return MemoryLevel.CRITICAL;
        if (percent >= properties.getWarningThresholdPercent())   return MemoryLevel.WARNING;
        return MemoryLevel.OK;
    }

    MetaspaceSnapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    private void evaluateAndLog(MetaspaceSnapshot snapshot) {
        MemoryLevel level = classify(snapshot.percent());
        Instant now = clock.instant();

        if (level == MemoryLevel.OK) {
            lastLoggedAt    = null;
            lastLoggedLevel = MemoryLevel.OK;
            return;
        }

        Duration rateLimit       = rateLimitFor(level);
        boolean levelChanged     = level != lastLoggedLevel;
        boolean rateLimitExpired = lastLoggedAt == null || now.isAfter(lastLoggedAt.plus(rateLimit));

        if (levelChanged || rateLimitExpired) {
            lastLoggedAt    = now;
            lastLoggedLevel = level;
            logPressure(level, snapshot);
        }
    }

    private void logPressure(MemoryLevel level, MetaspaceSnapshot snapshot) {
        String message = String.format(
            "Metaspace pressure %s: %d/%d MB used (%.1f%%), committed %d MB",
            level,
            snapshot.usedBytes()       / (1024 * 1024),
            snapshot.maxBytes()        / (1024 * 1024),
            snapshot.percent(),
            snapshot.committedBytes()  / (1024 * 1024)
        );
        switch (level) {
            case WARNING             -> log.warn(message);
            case CRITICAL, EMERGENCY -> log.error(message);
            default                  -> { }
        }
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
            Thread thread = new Thread(runnable, "bootlens-metaspace");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
