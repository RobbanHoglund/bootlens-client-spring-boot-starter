package com.bootlens.client.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

class LogErrorRateMonitor implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LogErrorRateMonitor.class);

    private static final Duration WARNING_RATELIMIT   = Duration.ofMinutes(10);
    private static final Duration CRITICAL_RATELIMIT  = Duration.ofMinutes(5);
    private static final Duration EMERGENCY_RATELIMIT = Duration.ofMinutes(2);

    private final LogErrorRateProperties properties;
    private final LoggerContext loggerContext;
    private final ScheduledExecutorService executorService;
    private final Clock clock;

    private final AtomicBoolean started   = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicReference<LogErrorRateSnapshot> lastSnapshot = new AtomicReference<>();

    final CountingAppender appender = new CountingAppender();

    private volatile long prevTotalErrors = 0;
    private volatile long prevTotalWarns  = 0;

    private volatile Instant lastLoggedAt;
    private volatile MemoryLevel lastLoggedLevel = MemoryLevel.OK;

    LogErrorRateMonitor(LogErrorRateProperties properties) {
        this(properties, (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory(), newDaemonExecutor(), Clock.systemUTC());
    }

    LogErrorRateMonitor(
        LogErrorRateProperties properties,
        LoggerContext loggerContext,
        ScheduledExecutorService executorService,
        Clock clock
    ) {
        this.properties    = properties;
        this.loggerContext = loggerContext;
        this.executorService = executorService;
        this.clock         = clock;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        appender.setContext(loggerContext);
        appender.setName("bootlens-log-error-rate");
        appender.start();
        loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).addAppender(appender);

        log.info(
            "Log error rate monitor active: interval={}, thresholds warning={} critical={} emergency={} errors/interval",
            properties.getCheckInterval(),
            properties.getWarningErrorsPerInterval(),
            properties.getCriticalErrorsPerInterval(),
            properties.getEmergencyErrorsPerInterval()
        );
        long intervalMillis = Math.max(5_000L, properties.getCheckInterval().toMillis());
        executorService.scheduleAtFixedRate(this::check, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
            executorService.shutdownNow();
        }
    }

    void check() {
        if (destroyed.get()) {
            return;
        }
        try {
            long nowErrors = appender.totalErrors.get();
            long nowWarns  = appender.totalWarns.get();

            long intervalErrors = nowErrors - prevTotalErrors;
            long intervalWarns  = nowWarns  - prevTotalWarns;

            prevTotalErrors = nowErrors;
            prevTotalWarns  = nowWarns;

            LogErrorRateSnapshot snapshot = new LogErrorRateSnapshot(
                intervalErrors, intervalWarns, nowErrors, nowWarns, clock.instant()
            );
            lastSnapshot.set(snapshot);
            evaluateAndLog(snapshot);
        }
        catch (Exception e) {
            log.debug("Log error rate check failed unexpectedly", e);
        }
    }

    MemoryLevel classify(long errorsInInterval) {
        if (errorsInInterval >= properties.getEmergencyErrorsPerInterval()) return MemoryLevel.EMERGENCY;
        if (errorsInInterval >= properties.getCriticalErrorsPerInterval())  return MemoryLevel.CRITICAL;
        if (errorsInInterval >= properties.getWarningErrorsPerInterval())   return MemoryLevel.WARNING;
        return MemoryLevel.OK;
    }

    LogErrorRateSnapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    private void evaluateAndLog(LogErrorRateSnapshot snapshot) {
        MemoryLevel level = classify(snapshot.errorsInInterval());
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
            logRate(level, snapshot);
        }
    }

    private void logRate(MemoryLevel level, LogErrorRateSnapshot snapshot) {
        String message = String.format(
            "Log error rate %s: %d ERROR and %d WARN log events in last interval (total %d errors / %d warns)",
            level,
            snapshot.errorsInInterval(),
            snapshot.warnsInInterval(),
            snapshot.totalErrors(),
            snapshot.totalWarns()
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
            Thread thread = new Thread(runnable, "bootlens-log-errors");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    static final class CountingAppender extends AppenderBase<ILoggingEvent> {
        final AtomicLong totalErrors = new AtomicLong();
        final AtomicLong totalWarns  = new AtomicLong();

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLevel() == Level.ERROR) {
                totalErrors.incrementAndGet();
            } else if (event.getLevel() == Level.WARN) {
                totalWarns.incrementAndGet();
            }
        }
    }
}
