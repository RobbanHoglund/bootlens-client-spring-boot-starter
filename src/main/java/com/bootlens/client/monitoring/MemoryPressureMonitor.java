package com.bootlens.client.monitoring;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
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

class MemoryPressureMonitor implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MemoryPressureMonitor.class);

    static final String DEFAULT_CGROUP_CURRENT = "/sys/fs/cgroup/memory.current";
    static final String DEFAULT_CGROUP_MAX = "/sys/fs/cgroup/memory.max";

    private static final Duration WARNING_RATELIMIT = Duration.ofMinutes(10);
    private static final Duration CRITICAL_RATELIMIT = Duration.ofMinutes(5);
    private static final Duration EMERGENCY_RATELIMIT = Duration.ofMinutes(2);

    private final MemoryPressureProperties properties;
    private final MemoryMXBean memoryMXBean;
    private final ScheduledExecutorService executorService;
    private final Clock clock;
    private final String cgroupCurrentPath;
    private final String cgroupMaxPath;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicBoolean cgroupAvailable = new AtomicBoolean(true);
    private final AtomicReference<MemorySnapshot> lastSnapshot = new AtomicReference<>();

    private volatile Instant lastLoggedAt;
    private volatile MemoryLevel lastLoggedLevel = MemoryLevel.OK;

    MemoryPressureMonitor(MemoryPressureProperties properties) {
        this(
            properties,
            ManagementFactory.getMemoryMXBean(),
            newDaemonExecutor(),
            Clock.systemUTC(),
            DEFAULT_CGROUP_CURRENT,
            DEFAULT_CGROUP_MAX
        );
    }

    MemoryPressureMonitor(
        MemoryPressureProperties properties,
        MemoryMXBean memoryMXBean,
        ScheduledExecutorService executorService,
        Clock clock,
        String cgroupCurrentPath,
        String cgroupMaxPath
    ) {
        this.properties = properties;
        this.memoryMXBean = memoryMXBean;
        this.executorService = executorService;
        this.clock = clock;
        this.cgroupCurrentPath = cgroupCurrentPath;
        this.cgroupMaxPath = cgroupMaxPath;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info(
            "Memory pressure monitor active: interval={}, thresholds warning={}% critical={}% emergency={}%",
            properties.getCheckInterval(),
            properties.getWarningThresholdPercent(),
            properties.getCriticalThresholdPercent(),
            properties.getEmergencyThresholdPercent()
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
            MemorySnapshot snapshot = buildSnapshot();
            lastSnapshot.set(snapshot);
            evaluateAndLog(snapshot);
        }
        catch (Exception e) {
            log.debug("Memory pressure check failed unexpectedly", e);
        }
    }

    MemorySnapshot buildSnapshot() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        long heapUsed = heap.getUsed();
        long heapMax = heap.getMax();
        double heapPercent = MemorySnapshot.percentOf(heapUsed, heapMax);

        Long containerUsed = null;
        Long containerMax = null;
        Double containerPercent = null;

        if (cgroupAvailable.get()) {
            try {
                containerUsed = readCgroupLong(cgroupCurrentPath);
                long rawMax = readCgroupLong(cgroupMaxPath);
                if (rawMax > 0 && rawMax != Long.MAX_VALUE) {
                    containerMax = rawMax;
                    containerPercent = MemorySnapshot.percentOf(containerUsed, containerMax);
                }
            }
            catch (IOException e) {
                cgroupAvailable.set(false);
                log.debug("cgroup v2 memory files not available; container memory metrics disabled");
                containerUsed = null;
            }
        }

        return new MemorySnapshot(heapUsed, heapMax, heapPercent, containerUsed, containerMax, containerPercent, clock.instant());
    }

    MemoryLevel classify(double percent) {
        if (percent >= properties.getEmergencyThresholdPercent()) return MemoryLevel.EMERGENCY;
        if (percent >= properties.getCriticalThresholdPercent()) return MemoryLevel.CRITICAL;
        if (percent >= properties.getWarningThresholdPercent()) return MemoryLevel.WARNING;
        return MemoryLevel.OK;
    }

    private void evaluateAndLog(MemorySnapshot snapshot) {
        double primaryPercent = snapshot.containerPercent() != null ? snapshot.containerPercent() : snapshot.heapPercent();
        MemoryLevel level = classify(primaryPercent);
        Instant now = clock.instant();

        if (level == MemoryLevel.OK) {
            lastLoggedAt = null;
            lastLoggedLevel = MemoryLevel.OK;
            return;
        }

        Duration rateLimit = rateLimitFor(level);
        boolean levelChanged = level != lastLoggedLevel;
        boolean rateLimitExpired = lastLoggedAt == null || now.isAfter(lastLoggedAt.plus(rateLimit));

        if (levelChanged || rateLimitExpired) {
            lastLoggedAt = now;
            lastLoggedLevel = level;
            logPressure(level, snapshot);
        }
    }

    private void logPressure(MemoryLevel level, MemorySnapshot snapshot) {
        String message = formatMessage(level, snapshot);
        switch (level) {
            case WARNING -> log.warn(message);
            case CRITICAL, EMERGENCY -> log.error(message);
            default -> { }
        }
    }

    private String formatMessage(MemoryLevel level, MemorySnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Memory pressure ").append(level).append(": ");
        sb.append("heap ").append(toMb(snapshot.heapUsed())).append("/").append(toMb(snapshot.heapMax())).append(" MB");
        sb.append(" (").append(String.format("%.1f", snapshot.heapPercent())).append("%)");
        if (snapshot.hasContainerMetrics()) {
            sb.append(", container ").append(toMb(snapshot.containerUsed())).append("/").append(toMb(snapshot.containerMax())).append(" MB");
            sb.append(" (").append(String.format("%.1f", snapshot.containerPercent())).append("%)");
        }
        return sb.toString();
    }

    MemorySnapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    private static long readCgroupLong(String path) throws IOException {
        String content = Files.readString(Path.of(path)).strip();
        if ("max".equalsIgnoreCase(content)) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(content);
    }

    private static long toMb(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static Duration rateLimitFor(MemoryLevel level) {
        return switch (level) {
            case WARNING -> WARNING_RATELIMIT;
            case CRITICAL -> CRITICAL_RATELIMIT;
            case EMERGENCY -> EMERGENCY_RATELIMIT;
            default -> Duration.ofMinutes(10);
        };
    }

    private static ScheduledExecutorService newDaemonExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "bootlens-memory-pressure");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
