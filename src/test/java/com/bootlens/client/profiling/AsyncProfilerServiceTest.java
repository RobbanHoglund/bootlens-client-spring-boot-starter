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
        assumeThat(service.status().profilerAvailable()).isFalse();

        AsyncProfilerService.StartResult result = service.start(null, null, null, null);
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.sessionId()).isNull();
        assertThat(result.message()).contains("async-profiler is not available");
    }

    @Test
    void stopReturnsUnavailableWhenNativeLibCannotLoad() {
        assumeThat(service.status().profilerAvailable()).isFalse();

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
            "cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);

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

        service.start("cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);

        AsyncProfilerService.ProfilerStatus status = service.status();
        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.activeSessionId()).isNotBlank();
        assertThat(status.activeEvent()).isEqualTo("cpu");
    }

    @Test
    void stopReturnsStoppedAndOutputFileExists() throws IOException {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult started = service.start(
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
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

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        service.stop();

        assertThat(service.status().state()).isEqualTo("IDLE");
        assertThat(service.status().lastSessionId()).isNotBlank();
        assertThat(service.status().lastSessionSucceeded()).isTrue();
    }

    @Test
    void startReturnsAlreadyRunningWhenSessionIsActive() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);

        AsyncProfilerService.StartResult second = service.start(
            "cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
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
            "cpu", Duration.ofMinutes(10), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.durationSeconds()).isEqualTo(60L); // clamped to max
    }

    @Test
    void defaultsAreAppliedWhenParametersAreNull() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(null, null, null, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.event()).isEqualTo(properties.getDefaultEvent());
        assertThat(result.format()).isEqualTo(properties.getDefaultFormat().name().toLowerCase());
        assertThat(result.durationSeconds()).isEqualTo(properties.getDefaultDuration().toSeconds());
        // Default cpu event → constant default interval
        assertThat(result.interval()).isEqualTo(ProfilerConstants.CPU_INTERVAL);
    }

    @Test
    void cpuEventUsesIntervalOption() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, "5ms");

        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.interval()).isEqualTo("5ms");
    }

    @Test
    void cpuDefaultIntervalIsAppliedWhenNoneSpecified() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        assertThat(result.interval()).isEqualTo(ProfilerConstants.CPU_INTERVAL);
    }

    @Test
    void wallDefaultIntervalIsApplied() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(
            "wall", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.interval()).isEqualTo(ProfilerConstants.WALL_INTERVAL);
    }

    @Test
    void allocDefaultIntervalIsApplied() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(
            "alloc", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.interval()).isEqualTo(ProfilerConstants.ALLOC_THRESHOLD);
    }

    @Test
    void lockDefaultIntervalIsApplied() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.StartResult result = service.start(
            "lock", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.interval()).isEqualTo(ProfilerConstants.LOCK_THRESHOLD);
    }

    @Test
    void eventNameIsNormalizedToExternalName() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        // "NATIVE_MEM" should resolve to "nativemem"
        AsyncProfilerService.StartResult result = service.start(
            "NATIVE_MEM", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.event()).isEqualTo("nativemem");
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
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR, null);
        assertThat(result.outputFile()).endsWith(".jfr");
        service.stop();
    }

    // --- In-memory dump APIs ---

    @Test
    void versionIsReturnedWhenProfilerIsAvailable() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.VersionResult result = service.getVersion();
        assertThat(result.available()).isTrue();
        assertThat(result.version()).isNotBlank();
    }

    @Test
    void versionIsUnavailableWhenNativeLibCannotLoad() {
        assumeThat(service.status().profilerAvailable()).isFalse();

        AsyncProfilerService.VersionResult result = service.getVersion();
        assertThat(result.available()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void samplesIsZeroBeforeSessionStarts() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        AsyncProfilerService.SamplesResult result = service.getSamples();
        assertThat(result.available()).isTrue();
        assertThat(result.samples()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void dumpFlatReturnsTextAfterSession() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        service.stop();

        AsyncProfilerService.DumpResult result = service.dumpFlat(10);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.type()).isEqualTo("flat");
        assertThat(result.data()).isNotNull();
    }

    @Test
    void dumpTracesReturnsTextAfterSession() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        service.stop();

        AsyncProfilerService.DumpResult result = service.dumpTraces(5);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.type()).isEqualTo("traces");
        assertThat(result.data()).isNotNull();
    }

    @Test
    void dumpCollapsedReturnsTextAfterSession() {
        assumeThat(service.status().profilerAvailable()).isTrue();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        service.stop();

        AsyncProfilerService.DumpResult result = service.dumpCollapsed();
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.type()).isEqualTo("collapsed");
        assertThat(result.data()).isNotNull();
    }

    @Test
    void dumpFlatReturnsUnavailableWhenNativeLibCannotLoad() {
        assumeThat(service.status().profilerAvailable()).isFalse();

        AsyncProfilerService.DumpResult result = service.dumpFlat(null);
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
    }
}
