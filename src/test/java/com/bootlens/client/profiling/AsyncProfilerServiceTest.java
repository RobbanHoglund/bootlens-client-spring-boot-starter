package com.bootlens.client.profiling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsyncProfilerServiceTest {

    @TempDir
    Path tempDir;

    AsyncProfilerProperties properties;
    AsyncProfilerService service;

    @BeforeEach
    void setUp() {
        properties = new AsyncProfilerProperties();
        properties.setOutputDir(tempDir.toString());
        properties.setDefaultDuration(Duration.ofSeconds(10));
        properties.setMaxDuration(Duration.ofSeconds(60));
        service = new AsyncProfilerService(properties);
    }

    @AfterEach
    void tearDown() {
        service.stop();   // ensure no session leaks between tests
        service.shutdown();
    }

    // --- Status when profiler is unavailable (e.g. Windows CI) ---

    @Test
    void statusIsUnavailableWhenNativeLibCannotLoad() {
        AsyncProfilerService.ProfilerStatus status = service.status();
        // On unsupported platforms the service degrades gracefully
        if (!status.profilerAvailable()) {
            assertThat(status.state()).isEqualTo("UNAVAILABLE");
            assertThat(status.profilerLoadError()).isNotBlank();
        }
    }

    @Test
    void startReturnsUnavailableWhenNativeLibCannotLoad() {
        AsyncProfilerService.ProfilerStatus status = service.status();
        assumeThat(status.profilerAvailable()).isFalse();

        AsyncProfilerService.StartResult result = service.start(null, null, null);
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.sessionId()).isNull();
        assertThat(result.message()).contains("async-profiler is not available");
    }

    @Test
    void stopReturnsUnavailableWhenNativeLibCannotLoad() {
        AsyncProfilerService.ProfilerStatus status = service.status();
        assumeThat(status.profilerAvailable()).isFalse();

        AsyncProfilerService.StopResult result = service.stop();
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
    }

    // --- Tests that only run when async-profiler native lib is loaded ---

    @Test
    void statusIsIdleBeforeAnySession() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.ProfilerStatus status = service.status();
        assertThat(status.state()).isEqualTo("IDLE");
        assertThat(status.activeSessionId()).isNull();
    }

    @Test
    void startReturnsStartedWithSessionId() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH);

        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.sessionId()).isNotBlank();
        assertThat(result.event()).isEqualTo("cpu");
        assertThat(result.format()).isEqualTo("flamegraph");
        assertThat(result.durationSeconds()).isEqualTo(5L);
        assertThat(result.outputFile()).endsWith(".html");
    }

    @Test
    void statusIsRunningAfterStart() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        service.start("cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH);

        AsyncProfilerService.ProfilerStatus status = service.status();
        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.activeSessionId()).isNotBlank();
        assertThat(status.activeEvent()).isEqualTo("cpu");
    }

    @Test
    void stopReturnsStoppedAndOutputFileExists() throws IOException {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult started = service.start(
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH);
        assertThat(started.status()).isEqualTo("STARTED");

        AsyncProfilerService.StopResult stopped = service.stop();
        assertThat(stopped.status()).isEqualTo("STOPPED");
        assertThat(stopped.sessionId()).isEqualTo(started.sessionId());
        assertThat(stopped.outputFile()).isNotBlank();
        assertThat(Files.exists(Path.of(stopped.outputFile()))).isTrue();
        assertThat(Files.size(Path.of(stopped.outputFile()))).isGreaterThan(0);
    }

    @Test
    void statusIsIdleAfterStop() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH);
        service.stop();

        assertThat(service.status().state()).isEqualTo("IDLE");
        assertThat(service.status().lastSessionId()).isNotBlank();
        assertThat(service.status().lastSessionSucceeded()).isTrue();
    }

    @Test
    void startReturnsAlreadyRunningWhenSessionIsActive() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH);

        AsyncProfilerService.StartResult second = service.start(
            "cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH);
        assertThat(second.status()).isEqualTo("ALREADY_RUNNING");
        assertThat(second.sessionId()).isNotBlank();
    }

    @Test
    void stopReturnsNotRunningWhenNoSessionIsActive() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StopResult result = service.stop();
        assertThat(result.status()).isEqualTo("NOT_RUNNING");
    }

    @Test
    void durationIsClampedToMaxDuration() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        // Request 10 minutes but max is 60 seconds
        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofMinutes(10), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.durationSeconds()).isEqualTo(60L); // clamped to max
    }

    @Test
    void defaultsAreAppliedWhenParametersAreNull() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(null, null, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.event()).isEqualTo(properties.getDefaultEvent());
        assertThat(result.format()).isEqualTo(
            properties.getDefaultFormat().name().toLowerCase());
        assertThat(result.durationSeconds()).isEqualTo(
            properties.getDefaultDuration().toSeconds());
    }

    @Test
    void resolveOutputFileRejectsDotDotTraversal() {
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
            () -> service.resolveOutputFile("../secret.txt"));
    }

    @Test
    void jfrOutputFileHasJfrExtension() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR);
        assertThat(result.outputFile()).endsWith(".jfr");
        service.stop();
    }
}
