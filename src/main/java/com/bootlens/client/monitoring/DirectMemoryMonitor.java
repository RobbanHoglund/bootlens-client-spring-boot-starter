package com.bootlens.client.monitoring;

import java.lang.management.BufferPoolMXBean;
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

class DirectMemoryMonitor implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DirectMemoryMonitor.class);

    private static final Duration WARNING_RATELIMIT   = Duration.ofMinutes(10);
    private static final Duration CRITICAL_RATELIMIT  = Duration.ofMinutes(5);
    private static final Duration EMERGENCY_RATELIMIT = Duration.ofMinutes(2);

    private final DirectMemoryProperties properties;
    private final ScheduledExecutorService executorService;
    private final Clock clock;

    private final AtomicBoolean started   = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicReference<DirectMemorySnapshot> lastSnapshot = new AtomicReference<>();

    private volatile Instant lastLoggedAt;
    private volatile MemoryLevel lastLoggedLevel = MemoryLevel.OK;

    private final long maxDirectMemoryBytes;

    DirectMemoryMonitor(DirectMemoryProperties properties) {
        this(properties, newDaemonExecutor(), Clock.systemUTC());
    }

    DirectMemoryMonitor(DirectMemoryProperties properties, ScheduledExecutorService executorService, Clock clock) {
        this.properties      = properties;
        this.executorService = executorService;
        this.clock           = clock;
        this.maxDirectMemoryBytes = resolveMaxDirectMemory();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (maxDirectMemoryBytes > 0) {
            log.info(
                "Direct memory monitor active: interval={}, thresholds warning={}% critical={}% emergency={}%, maxDirectMemory={}MB",
                properties.getCheckInterval(),
                properties.getWarningThresholdPercent(),
                properties.getCriticalThresholdPercent(),
                properties.getEmergencyThresholdPercent(),
                maxDirectMemoryBytes / (1024 * 1024)
            );
        } else {
            log.info(
                "Direct memory monitor active: interval={}, thresholds warning={}% critical={}% emergency={}% (max direct memory not determinable — usage reported in bytes only)",
                properties.getCheckInterval(),
                properties.getWarningThresholdPercent(),
                properties.getCriticalThresholdPercent(),
                properties.getEmergencyThresholdPercent()
            );
        }
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
            DirectMemorySnapshot snapshot = buildSnapshot();
            lastSnapshot.set(snapshot);
            if (snapshot.available()) {
                evaluateAndLog(snapshot);
            }
        }
        catch (Exception e) {
            log.debug("Direct memory check failed unexpectedly", e);
        }
    }

    DirectMemorySnapshot buildSnapshot() {
        List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        BufferPoolMXBean direct = pools.stream()
            .filter(p -> "direct".equals(p.getName()))
            .findFirst()
            .orElse(null);

        if (direct == null) {
            return DirectMemorySnapshot.unavailable(clock.instant());
        }

        long count    = direct.getCount();
        long used     = direct.getMemoryUsed();
        long capacity = direct.getTotalCapacity();

        if (maxDirectMemoryBytes <= 0) {
            return new DirectMemorySnapshot(count, used, capacity, -1, 0.0, false, clock.instant());
        }

        double percent = DirectMemorySnapshot.percentOf(used, maxDirectMemoryBytes);
        return new DirectMemorySnapshot(count, used, capacity, maxDirectMemoryBytes, percent, true, clock.instant());
    }

    MemoryLevel classify(double percent) {
        if (percent >= properties.getEmergencyThresholdPercent()) return MemoryLevel.EMERGENCY;
        if (percent >= properties.getCriticalThresholdPercent())  return MemoryLevel.CRITICAL;
        if (percent >= properties.getWarningThresholdPercent())   return MemoryLevel.WARNING;
        return MemoryLevel.OK;
    }

    DirectMemorySnapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    private void evaluateAndLog(DirectMemorySnapshot snapshot) {
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

    private void logPressure(MemoryLevel level, DirectMemorySnapshot snapshot) {
        String message = String.format(
            "Direct memory pressure %s: %d/%d MB used (%.1f%%), %d buffers, capacity %d MB",
            level,
            snapshot.usedBytes()     / (1024 * 1024),
            snapshot.maxBytes()      / (1024 * 1024),
            snapshot.percent(),
            snapshot.bufferCount(),
            snapshot.capacityBytes() / (1024 * 1024)
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

    private static long resolveMaxDirectMemory() {
        try {
            Class<?> vm = Class.forName("sun.misc.VM");
            return (long) vm.getMethod("maxDirectMemory").invoke(null);
        }
        catch (Exception e) {
            return -1L;
        }
    }

    private static ScheduledExecutorService newDaemonExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "bootlens-direct-memory");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
