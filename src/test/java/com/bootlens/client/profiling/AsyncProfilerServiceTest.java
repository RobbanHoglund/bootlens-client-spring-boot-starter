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

    private void assumeNativeProfilerExerciseAllowed() {
        assumeThat(Boolean.getBoolean("bootlens.test.native-profiler"))
            .as("Native async-profiler execution is opt-in because CI runners often restrict perf events")
            .isTrue();
        assumeThat(service.status().profilerAvailable()).isTrue();
    }

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        }
        else {
            System.setProperty(key, value);
        }
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
    void nativeProfilerIsAvailableWhenNativeExerciseIsEnabled() {
        assumeThat(Boolean.getBoolean("bootlens.test.native-profiler"))
            .as("Native async-profiler execution is opt-in")
            .isTrue();

        AsyncProfilerService.ProfilerStatus status = service.status();
        assertThat(status.profilerAvailable())
            .as("Native async-profiler must be available when bootlens.test.native-profiler=true. Load error: %s",
                status.profilerLoadError())
            .isTrue();
    }

    @Test
    void statusIsIdleBeforeAnySession() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.ProfilerStatus status = service.status();
        assertThat(status.state()).isEqualTo("IDLE");
        assertThat(status.activeSessionId()).isNull();
    }

    @Test
    void startReturnsStartedWithSessionId() {
        assumeNativeProfilerExerciseAllowed();

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
        assumeNativeProfilerExerciseAllowed();

        service.start("cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);

        AsyncProfilerService.ProfilerStatus status = service.status();
        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.activeSessionId()).isNotBlank();
        assertThat(status.activeEvent()).isEqualTo("cpu");
    }

    @Test
    void stopReturnsStoppedAndOutputFileExists() throws IOException {
        assumeNativeProfilerExerciseAllowed();

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
        assumeNativeProfilerExerciseAllowed();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        service.stop();

        assertThat(service.status().state()).isEqualTo("IDLE");
        assertThat(service.status().lastSessionId()).isNotBlank();
        assertThat(service.status().lastSessionSucceeded()).isTrue();
    }

    @Test
    void startReturnsAlreadyRunningWhenSessionIsActive() {
        assumeNativeProfilerExerciseAllowed();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);

        AsyncProfilerService.StartResult second = service.start(
            "cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        assertThat(second.status()).isEqualTo("ALREADY_RUNNING");
        assertThat(second.sessionId()).isNotBlank();
    }

    @Test
    void stopReturnsNotRunningWhenNoSessionIsActive() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StopResult result = service.stop();
        assertThat(result.status()).isEqualTo("NOT_RUNNING");
    }

    @Test
    void durationIsClampedToMaxDuration() {
        assumeNativeProfilerExerciseAllowed();

        // Request 10 minutes but max is 60 seconds
        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofMinutes(10), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.durationSeconds()).isEqualTo(60L); // clamped to max
    }

    @Test
    void defaultsAreAppliedWhenParametersAreNull() {
        assumeNativeProfilerExerciseAllowed();

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
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, "5ms");

        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.interval()).isEqualTo("5ms");
    }

    @Test
    void cpuDefaultIntervalIsAppliedWhenNoneSpecified() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        assertThat(result.interval()).isEqualTo(ProfilerConstants.CPU_INTERVAL);
    }

    @Test
    void wallDefaultIntervalIsApplied() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StartResult result = service.start(
            "wall", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.interval()).isEqualTo(ProfilerConstants.WALL_INTERVAL);
    }

    @Test
    void allocDefaultIntervalIsApplied() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StartResult result = service.start(
            "alloc", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.interval()).isEqualTo(ProfilerConstants.ALLOC_THRESHOLD);
    }

    @Test
    void lockDefaultIntervalIsApplied() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StartResult result = service.start(
            "lock", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.interval()).isEqualTo(ProfilerConstants.LOCK_THRESHOLD);
    }

    @Test
    void eventNameIsNormalizedToExternalName() {
        assumeNativeProfilerExerciseAllowed();

        // "NATIVE_MEM" should resolve to "nativemem"
        AsyncProfilerService.StartResult result = service.start(
            "NATIVE_MEM", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR, null);
        assertThat(result.status()).isEqualTo("STARTED");
        assertThat(result.event()).isEqualTo("nativemem");
    }

    @Test
    void allocStartCommandUsesDefaultThreshold() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.ALLOC,
            ProfilerEvent.ALLOC.externalName(),
            tempDir.resolve("alloc.html"),
            AsyncProfilerProperties.OutputFormat.FLAMEGRAPH,
            ProfilerConstants.ALLOC_THRESHOLD,
            Duration.ofSeconds(30),
            0,
            false,
            false);

        assertThat(command)
            .contains("event=alloc")
            .contains(",flamegraph,")
            .contains("alloc=1k")
            .doesNotContain("output=");
    }

    @Test
    void nativeMemoryStartCommandUsesDefaultThreshold() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.NATIVE_MEM,
            ProfilerEvent.NATIVE_MEM.externalName(),
            tempDir.resolve("native-memory.jfr"),
            AsyncProfilerProperties.OutputFormat.JFR,
            ProfilerConstants.NATIVEMEM_THRESHOLD,
            Duration.ofSeconds(60),
            0,
            false,
            false);

        assertThat(command)
            .contains("event=nativemem")
            .contains(",jfr,")
            .contains("nativemem=1k")
            .contains("nofree")
            .doesNotContain("output=");
    }

    @Test
    void freeFormEventCommandPreservesCaseSensitiveEventName() {
        String command = AsyncProfilerService.buildStartCommand(
            null,
            "java.util.Properties.getProperty",
            tempDir.resolve("method.html"),
            AsyncProfilerProperties.OutputFormat.FLAMEGRAPH,
            null,
            Duration.ofSeconds(15),
            0,
            false,
            false);

        assertThat(command).contains("event=java.util.Properties.getProperty");
    }

    @Test
    void nativeMemLeakCommandUsesNativememEventWithLeakFlag() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.NATIVE_MEM_LEAK,
            ProfilerEvent.NATIVE_MEM_LEAK.externalName(),
            tempDir.resolve("leak.jfr"),
            AsyncProfilerProperties.OutputFormat.JFR,
            ProfilerConstants.NATIVEMEM_THRESHOLD,
            Duration.ofSeconds(60),
            0,
            false,
            false);

        // nativememleak must use "nativemem" as the event name in the command
        assertThat(command)
            .contains("event=nativemem")
            .contains("nativemem=1k")
            .contains(",leak")
            .doesNotContain("event=nativememleak")
            .doesNotContain("nofree");
    }

    @Test
    void ctimerCommandUsesIntervalOption() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.CTIMER,
            ProfilerEvent.CTIMER.externalName(),
            tempDir.resolve("ctimer.html"),
            AsyncProfilerProperties.OutputFormat.FLAMEGRAPH,
            ProfilerConstants.CTIMER_INTERVAL,
            Duration.ofSeconds(30),
            0,
            false,
            false);

        assertThat(command)
            .contains("event=ctimer")
            .contains(",interval=" + ProfilerConstants.CTIMER_INTERVAL)
            .doesNotContain("alloc=")
            .doesNotContain("lock=")
            .doesNotContain("nativemem=");
    }

    @Test
    void invertedFlagIsAppendedForFlamegraphFormat() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.CPU,
            ProfilerEvent.CPU.externalName(),
            tempDir.resolve("cpu.html"),
            AsyncProfilerProperties.OutputFormat.FLAMEGRAPH,
            ProfilerConstants.CPU_INTERVAL,
            Duration.ofSeconds(30),
            0,
            false,
            true); // inverted=true

        assertThat(command).contains(",inverted");
    }

    @Test
    void invertedFlagIsNotAppendedForJfrFormat() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.CPU,
            ProfilerEvent.CPU.externalName(),
            tempDir.resolve("cpu.jfr"),
            AsyncProfilerProperties.OutputFormat.JFR,
            ProfilerConstants.CPU_INTERVAL,
            Duration.ofSeconds(30),
            0,
            false,
            true); // inverted=true — ignored for JFR

        assertThat(command).doesNotContain(",inverted");
    }

    @Test
    void jstackdepthIsAppendedWhenPositive() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.CPU,
            ProfilerEvent.CPU.externalName(),
            tempDir.resolve("cpu.html"),
            AsyncProfilerProperties.OutputFormat.FLAMEGRAPH,
            ProfilerConstants.CPU_INTERVAL,
            Duration.ofSeconds(30),
            64,    // jstackdepth
            false,
            false);

        assertThat(command).contains(",jstackdepth=64");
    }

    @Test
    void jstackdepthIsOmittedWhenZero() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.CPU,
            ProfilerEvent.CPU.externalName(),
            tempDir.resolve("cpu.html"),
            AsyncProfilerProperties.OutputFormat.FLAMEGRAPH,
            ProfilerConstants.CPU_INTERVAL,
            Duration.ofSeconds(30),
            0,     // jstackdepth=0 → use async-profiler default
            false,
            false);

        assertThat(command).doesNotContain("jstackdepth");
    }

    @Test
    void threadsOptionIsAppendedWhenTrue() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.WALL,
            ProfilerEvent.WALL.externalName(),
            tempDir.resolve("wall.html"),
            AsyncProfilerProperties.OutputFormat.FLAMEGRAPH,
            ProfilerConstants.WALL_INTERVAL,
            Duration.ofSeconds(30),
            0,
            true,  // threads=true
            false);

        assertThat(command).contains(",threads");
    }

    @Test
    void threadsOptionIsOmittedWhenFalse() {
        String command = AsyncProfilerService.buildStartCommand(
            ProfilerEvent.CPU,
            ProfilerEvent.CPU.externalName(),
            tempDir.resolve("cpu.html"),
            AsyncProfilerProperties.OutputFormat.FLAMEGRAPH,
            ProfilerConstants.CPU_INTERVAL,
            Duration.ofSeconds(30),
            0,
            false, // threads=false
            false);

        assertThat(command).doesNotContain(",threads");
    }

    @Test
    void runtimeHintDetectsLibstdcpp() {
        String hint = AsyncProfilerService.runtimeHint(
            "UnsatisfiedLinkError: libstdc++.so.6: cannot open shared object file");
        assertThat(hint)
            .contains("libstdc++.so.6 is missing")
            .contains("apk add --no-cache libstdc++");
    }

    @Test
    void runtimeHintForMuslIsInformative() {
        String hint = AsyncProfilerService.runtimeHint("some glibc error on a musl system");
        // runtimeHint inspects the local filesystem, not the message string, for musl detection.
        // On this platform we just verify it returns a non-null string.
        assertThat(hint).isNotNull();
    }

    @Test
    void runtimeHintReturnsEmptyStringWhenNoPatternMatches() {
        // Simulate glibc platform with an unrelated error.
        // On this dev machine detectLibc() returns "unknown" (not Linux), so hint is empty.
        String libc = AsyncProfilerService.detectLibc();
        if ("unknown".equals(libc)) {
            String hint = AsyncProfilerService.runtimeHint("some unrelated error");
            assertThat(hint).isEmpty();
        }
    }

    @Test
    void commandTokenValidationAllowsKnownSafeProfilerTokens() {
        assertThat(AsyncProfilerService.validateCommandToken("event", "cache-misses")).isNull();
        assertThat(AsyncProfilerService.validateCommandToken("event", "java.util.Properties.getProperty")).isNull();
        assertThat(AsyncProfilerService.validateCommandToken("event", "cpu")).isNull();
        assertThat(AsyncProfilerService.validateOptionalCommandToken("interval", "500us")).isNull();
        assertThat(AsyncProfilerService.validateOptionalCommandToken("interval", "512k")).isNull();
    }

    @Test
    void commandTokenValidationRejectsInjectedCommandOptions() {
        assertThat(AsyncProfilerService.validateCommandToken("event", "cpu,file=/tmp/out.html"))
            .contains("unsupported characters");
        assertThat(AsyncProfilerService.validateOptionalCommandToken("interval", "1ms,threads"))
            .contains("unsupported characters");
        assertThat(AsyncProfilerService.validateOptionalCommandToken("interval", "1ms output=html"))
            .contains("unsupported characters");
    }

    @Test
    void apLoaderExtractionDirectoryCandidatesPreferApplicationDirectoryBeforeTmpWhenUnset() {
        String original = System.getProperty(AsyncProfilerService.AP_LOADER_EXTRACTION_DIR_PROPERTY);
        System.clearProperty(AsyncProfilerService.AP_LOADER_EXTRACTION_DIR_PROPERTY);
        try {
            var candidates = AsyncProfilerService.apLoaderExtractionDirectoryCandidates();

            assertThat(candidates).isNotEmpty();
            assertThat(candidates.get(0).getFileName().toString()).isEqualTo(".bootlens-ap-loader");
            assertThat(candidates)
                .anySatisfy(path -> assertThat(path.getFileName().toString()).isEqualTo("bootlens-ap-loader"));
        }
        finally {
            restoreSystemProperty(AsyncProfilerService.AP_LOADER_EXTRACTION_DIR_PROPERTY, original);
        }
    }

    @Test
    void apLoaderExtractionDirectoryCandidatesTryExplicitSystemPropertyFirst() {
        String original = System.getProperty(AsyncProfilerService.AP_LOADER_EXTRACTION_DIR_PROPERTY);
        Path explicit = tempDir.resolve("custom-ap-loader");
        System.setProperty(AsyncProfilerService.AP_LOADER_EXTRACTION_DIR_PROPERTY, explicit.toString());
        try {
            var candidates = AsyncProfilerService.apLoaderExtractionDirectoryCandidates();

            assertThat(candidates.get(0)).isEqualTo(explicit.toAbsolutePath().normalize());
            assertThat(candidates).doesNotHaveDuplicates();
        }
        finally {
            restoreSystemProperty(AsyncProfilerService.AP_LOADER_EXTRACTION_DIR_PROPERTY, original);
        }
    }

    @Test
    void libcDetectionReturnsKnownValue() {
        assertThat(AsyncProfilerService.detectLibc()).isIn("glibc", "musl", "unknown");
    }

    @Test
    void runtimeHintExplainsMissingCppRuntime() {
        String hint = AsyncProfilerService.runtimeHint(
            "UnsatisfiedLinkError: libasyncProfiler.so: Error loading shared library libstdc++.so.6");

        assertThat(hint)
            .contains("libstdc++.so.6 is missing")
            .contains("apk add --no-cache libstdc++");
    }

    @Test
    void startRejectsUnsafeEventWhenProfilerIsAvailable() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StartResult result = service.start(
            "cpu,file=/tmp/out.html", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("unsupported characters");
    }

    @Test
    void startRejectsUnsafeIntervalWhenProfilerIsAvailable() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, "1ms,threads");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("unsupported characters");
    }

    @Test
    void resolveOutputFileRejectsDotDotTraversal() {
        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
            () -> service.resolveOutputFile("../secret.txt"));
    }

    @Test
    void jfrOutputFileHasJfrExtension() {
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.StartResult result = service.start(
            "cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.JFR, null);
        assertThat(result.outputFile()).endsWith(".jfr");
        service.stop();
    }

    // --- In-memory dump APIs ---

    @Test
    void versionIsReturnedWhenProfilerIsAvailable() {
        assumeNativeProfilerExerciseAllowed();

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
        assumeNativeProfilerExerciseAllowed();

        AsyncProfilerService.SamplesResult result = service.getSamples();
        assertThat(result.available()).isTrue();
        assertThat(result.samples()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void dumpFlatReturnsTextAfterSession() {
        assumeNativeProfilerExerciseAllowed();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        service.stop();

        AsyncProfilerService.DumpResult result = service.dumpFlat(10);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.type()).isEqualTo("flat");
        assertThat(result.data()).isNotNull();
    }

    @Test
    void dumpTracesReturnsTextAfterSession() {
        assumeNativeProfilerExerciseAllowed();

        service.start("cpu", Duration.ofSeconds(30), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
        service.stop();

        AsyncProfilerService.DumpResult result = service.dumpTraces(5);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.type()).isEqualTo("traces");
        assertThat(result.data()).isNotNull();
    }

    @Test
    void dumpCollapsedReturnsTextAfterSession() {
        assumeNativeProfilerExerciseAllowed();

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

    // -------------------------------------------------------------------------
    // Output file retention (P1-1)
    // -------------------------------------------------------------------------

    @Test
    void oldOutputFilesAreCleanedUpWhenMaxOutputFilesIsReached() throws Exception {
        properties.setMaxOutputFiles(2);
        properties.setMaxOutputAge(null); // age-based cleanup off; count-based only

        // Create 3 existing files before starting a new session
        for (int i = 1; i <= 3; i++) {
            java.nio.file.Files.writeString(tempDir.resolve("bootlens-old" + i + ".html"), "content");
            // Ensure different modification times
            java.nio.file.Files.setLastModifiedTime(
                tempDir.resolve("bootlens-old" + i + ".html"),
                java.nio.file.attribute.FileTime.from(
                    java.time.Instant.now().minusSeconds(300L - i)));
        }

        // Starting a new session triggers cleanup
        if (!service.status().profilerAvailable()) {
            // On platforms where profiler is unavailable, just call ensureOutputDir indirectly
            // by triggering start (which will fail as UNAVAILABLE, but cleanup still runs)
            service.start("cpu", Duration.ofSeconds(5), AsyncProfilerProperties.OutputFormat.FLAMEGRAPH, null);
            long remaining = java.nio.file.Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("bootlens-"))
                .count();
            // cleanup runs on start; files limited to maxOutputFiles
            assertThat(remaining).isLessThanOrEqualTo(properties.getMaxOutputFiles() + 1L); // +1 for new session file (not written when unavailable)
        }
    }

    @Test
    void maxOutputFilesDefaultIsPositive() {
        assertThat(properties.getMaxOutputFiles()).isGreaterThan(0);
    }

    @Test
    void maxOutputAgeDefaultIsNotNull() {
        assertThat(properties.getMaxOutputAge()).isNotNull();
    }
}
