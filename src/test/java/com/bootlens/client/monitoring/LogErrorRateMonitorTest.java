package com.bootlens.client.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;

class LogErrorRateMonitorTest {

    ScheduledExecutorService executorService;
    LoggerContext loggerContext;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadScheduledExecutor();
        loggerContext   = (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    // --- classify ---

    @Test
    void classifyReturnsOkBelowWarning() {
        assertThat(buildMonitor().classify(9)).isEqualTo(MemoryLevel.OK);
    }

    @Test
    void classifyReturnsWarningAtThreshold() {
        assertThat(buildMonitor().classify(10)).isEqualTo(MemoryLevel.WARNING);
    }

    @Test
    void classifyReturnsCriticalAtThreshold() {
        assertThat(buildMonitor().classify(50)).isEqualTo(MemoryLevel.CRITICAL);
    }

    @Test
    void classifyReturnsEmergencyAtThreshold() {
        assertThat(buildMonitor().classify(200)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    @Test
    void customThresholdsAreRespected() {
        LogErrorRateProperties props = new LogErrorRateProperties();
        props.setWarningErrorsPerInterval(5);
        props.setCriticalErrorsPerInterval(20);
        props.setEmergencyErrorsPerInterval(100);
        LogErrorRateMonitor monitor = new LogErrorRateMonitor(props, loggerContext, executorService, fixedClock());

        assertThat(monitor.classify(4)).isEqualTo(MemoryLevel.OK);
        assertThat(monitor.classify(5)).isEqualTo(MemoryLevel.WARNING);
        assertThat(monitor.classify(20)).isEqualTo(MemoryLevel.CRITICAL);
        assertThat(monitor.classify(100)).isEqualTo(MemoryLevel.EMERGENCY);
    }

    // --- CountingAppender ---

    @Test
    void countingAppenderCountsErrorsAndWarns() {
        LogErrorRateMonitor.CountingAppender appender = new LogErrorRateMonitor.CountingAppender();
        appender.setContext(loggerContext);
        appender.start();

        ch.qos.logback.classic.spi.LoggingEvent error = makeEvent(ch.qos.logback.classic.Level.ERROR);
        ch.qos.logback.classic.spi.LoggingEvent warn  = makeEvent(ch.qos.logback.classic.Level.WARN);
        ch.qos.logback.classic.spi.LoggingEvent info  = makeEvent(ch.qos.logback.classic.Level.INFO);

        appender.doAppend(error);
        appender.doAppend(error);
        appender.doAppend(warn);
        appender.doAppend(info);

        assertThat(appender.totalErrors.get()).isEqualTo(2);
        assertThat(appender.totalWarns.get()).isEqualTo(1);

        appender.stop();
    }

    // --- check() delta logic ---

    @Test
    void checkAccumulatesErrorDeltasBetweenChecks() {
        LogErrorRateMonitor monitor = buildMonitor();
        // Attach the counting appender by simulating startup (attach manually for test)
        LogErrorRateMonitor.CountingAppender appender = monitor.appender;
        appender.setContext(loggerContext);
        appender.start();

        // Simulate 15 errors having occurred
        for (int i = 0; i < 15; i++) appender.totalErrors.incrementAndGet();

        monitor.check();

        LogErrorRateSnapshot snapshot = monitor.lastSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.errorsInInterval()).isEqualTo(15);
        assertThat(snapshot.totalErrors()).isEqualTo(15);

        // Simulate 5 more errors in next interval
        for (int i = 0; i < 5; i++) appender.totalErrors.incrementAndGet();
        monitor.check();

        snapshot = monitor.lastSnapshot();
        assertThat(snapshot.errorsInInterval()).isEqualTo(5);
        assertThat(snapshot.totalErrors()).isEqualTo(20);

        appender.stop();
    }

    // --- lifecycle ---

    @Test
    void lastSnapshotIsNullBeforeFirstCheck() {
        assertThat(buildMonitor().lastSnapshot()).isNull();
    }

    @Test
    void destroyStopsExecutor() {
        LogErrorRateMonitor monitor = buildMonitor();
        monitor.destroy();
        assertThat(executorService.isShutdown()).isTrue();
    }

    // --- Helpers ---

    private LogErrorRateMonitor buildMonitor() {
        return new LogErrorRateMonitor(new LogErrorRateProperties(), loggerContext, executorService, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    private static ch.qos.logback.classic.spi.LoggingEvent makeEvent(ch.qos.logback.classic.Level level) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("test");
        ch.qos.logback.classic.spi.LoggingEvent event =
            new ch.qos.logback.classic.spi.LoggingEvent();
        event.setLevel(level);
        event.setLoggerName("test");
        return event;
    }
}
