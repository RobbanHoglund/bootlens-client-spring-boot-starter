package com.bootlens.client.monitoring;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

class FileDescriptorMonitor implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FileDescriptorMonitor.class);

    private static final Duration WARNING_RATELIMIT   = Duration.ofMinutes(10);
    private static final Duration CRITICAL_RATELIMIT  = Duration.ofMinutes(5);
    private static final Duration EMERGENCY_RATELIMIT = Duration.ofMinutes(2);

    private final FileDescriptorProperties properties;
    private final OperatingSystemMXBean osMXBean;
    private final ScheduledExecutorService executorService;
    private final Clock clock;

    private final AtomicBoolean started   = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicReference<FileDescriptorSnapshot> lastSnapshot = new AtomicReference<>();

    private volatile Instant lastLoggedAt;
    private volatile MemoryLevel lastLoggedLevel = MemoryLevel.OK;

    FileDescriptorMonitor(FileDescriptorProperties properties) {
        this(properties, ManagementFactory.getOperatingSystemMXBean(), newDaemonExecutor(), Clock.systemUTC());
    }

    FileDescriptorMonitor(
        FileDescriptorProperties properties,
        OperatingSystemMXBean osMXBean,
        ScheduledExecutorService executorService,
        Clock clock
    ) {
        this.properties    = properties;
        this.osMXBean      = osMXBean;
        this.executorService = executorService;
        this.clock         = clock;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        FileDescriptorSnapshot probe = buildSnapshot();
        if (!probe.available()) {
            log.debug(
                "File descriptor monitoring not available on this platform (requires Unix/Linux with com.sun.management.UnixOperatingSystemMXBean)"
            );
        } else {
            log.info(
                "File descriptor monitor active: interval={}, thresholds warning={}% critical={}% emergency={}%, limit={}",
                properties.getCheckInterval(),
                properties.getWarningThresholdPercent(),
                properties.getCriticalThresholdPercent(),
                properties.getEmergencyThresholdPercent(),
                probe.max()
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
            FileDescriptorSnapshot snapshot = buildSnapshot();
            lastSnapshot.set(snapshot);
            if (snapshot.available()) {
                evaluateAndLog(snapshot);
            }
        }
        catch (Exception e) {
            log.debug("File descriptor check failed unexpectedly", e);
        }
    }

    FileDescriptorSnapshot buildSnapshot() {
        try {
            Class<?> unixClass = Class.forName("com.sun.management.UnixOperatingSystemMXBean");
            if (!unixClass.isInstance(osMXBean)) {
                return FileDescriptorSnapshot.unavailable(clock.instant());
            }
            long open = (long) unixClass.getMethod("getOpenFileDescriptorCount").invoke(osMXBean);
            long max  = (long) unixClass.getMethod("getMaxFileDescriptorCount").invoke(osMXBean);
            double percent = FileDescriptorSnapshot.percentOf(open, max);
            return new FileDescriptorSnapshot(open, max, percent, true, clock.instant());
        }
        catch (Exception e) {
            return FileDescriptorSnapshot.unavailable(clock.instant());
        }
    }

    MemoryLevel classify(double percent) {
        if (percent >= properties.getEmergencyThresholdPercent()) return MemoryLevel.EMERGENCY;
        if (percent >= properties.getCriticalThresholdPercent())  return MemoryLevel.CRITICAL;
        if (percent >= properties.getWarningThresholdPercent())   return MemoryLevel.WARNING;
        return MemoryLevel.OK;
    }

    FileDescriptorSnapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    private void evaluateAndLog(FileDescriptorSnapshot snapshot) {
        MemoryLevel level = classify(snapshot.percent());
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

    private void logPressure(MemoryLevel level, FileDescriptorSnapshot snapshot) {
        String message = String.format(
            "File descriptor pressure %s: %d/%d open (%.1f%%)",
            level, snapshot.open(), snapshot.max(), snapshot.percent()
        );
        switch (level) {
            case WARNING  -> log.warn(message);
            case CRITICAL, EMERGENCY -> log.error(message);
            default -> { }
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
            Thread thread = new Thread(runnable, "bootlens-fd-monitor");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
