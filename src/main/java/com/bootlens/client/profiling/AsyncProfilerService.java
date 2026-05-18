package com.bootlens.client.profiling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import one.profiler.AsyncProfiler;

import org.springframework.lang.Nullable;

/**
 * Service that manages embedded async-profiler sessions.
 *
 * <p>Only one profiling session can be active at a time per JVM. All public
 * methods are {@code synchronized} to guarantee mutual exclusion.
 *
 * <p>Each session is automatically stopped after the requested duration via
 * the native {@code timeout=} profiler option. A backup scheduler additionally
 * cleans up the {@link #activeSession} state after that time, so that
 * {@link #status()} correctly transitions from RUNNING to IDLE.
 */
public class AsyncProfilerService {

    private final AsyncProfilerProperties properties;
    private final ScheduledExecutorService scheduler;

    private final boolean profilerAvailable;
    @Nullable private final String profilerLoadError;

    // Non-null only while a session is active
    @Nullable private ActiveSession activeSession;

    // Result of the most recently completed session (null if no session has completed yet)
    @Nullable private CompletedSession lastCompleted;

    public AsyncProfilerService(AsyncProfilerProperties properties) {
        this.properties = properties;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bootlens-profiler-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        boolean available = false;
        String loadError = null;
        try {
            AsyncProfiler.getInstance(); // triggers native lib load
            available = true;
        }
        catch (UnsatisfiedLinkError | Exception ex) {
            loadError = ex.getMessage();
        }
        this.profilerAvailable = available;
        this.profilerLoadError = loadError;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts a new profiling session.
     *
     * @param event    profiling event (cpu, alloc, wall, lock, nativemem, …); null → default
     * @param duration profiling duration; null → default
     * @param format   output format; null → default
     * @param interval sampling interval — meaning depends on the event type:
     *                 <ul>
     *                   <li>CPU / wall-clock: time between samples, e.g. {@code "10ms"} or {@code "1000000"} (ns)</li>
     *                   <li>alloc: allocation size between samples, e.g. {@code "512k"} or {@code "1m"}</li>
     *                   <li>lock: lock-wait threshold, e.g. {@code "10ms"}</li>
     *                 </ul>
     *                 Accepts the same unit suffixes as async-profiler: ns, us, ms, s, k, m, g.
     *                 {@code null} → use configured default (see {@code bootlens.client.profiler.default-interval}).
     * @return result describing the started session
     */
    public synchronized StartResult start(
        @Nullable String event,
        @Nullable Duration duration,
        @Nullable AsyncProfilerProperties.OutputFormat format,
        @Nullable String interval
    ) {
        if (!profilerAvailable) {
            return StartResult.unavailable(profilerLoadError);
        }
        if (activeSession != null) {
            return StartResult.alreadyRunning(activeSession.sessionId());
        }

        String resolvedEvent    = event    != null ? event    : properties.getDefaultEvent();
        Duration resolvedDuration = clampDuration(
            duration != null ? duration : properties.getDefaultDuration());
        AsyncProfilerProperties.OutputFormat resolvedFormat =
            format != null ? format : properties.getDefaultFormat();
        String resolvedInterval = interval != null ? interval : properties.getDefaultInterval();

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path outputDir = ensureOutputDir();
        String fileName = "bootlens-" + sessionId + resolvedFormat.getFileExtension();
        Path outputFile = outputDir.resolve(fileName);

        String command = buildStartCommand(resolvedEvent, outputFile, resolvedFormat,
            resolvedInterval, resolvedDuration, properties.getJstackdepth(), properties.isThreads());
        try {
            AsyncProfiler.getInstance().execute(command);
        }
        catch (Exception ex) {
            return StartResult.failed(ex.getMessage());
        }

        // Backup scheduler: async-profiler stops itself via timeout=, but we need to
        // update our internal state (activeSession → lastCompleted) when time is up.
        // Add a small grace period so async-profiler's own stop runs first.
        ScheduledFuture<?> stateCleanup = scheduler.schedule(
            this::autoStop,
            resolvedDuration.toMillis() + 1_000L,   // 1 s grace after native timeout
            TimeUnit.MILLISECONDS);

        activeSession = new ActiveSession(sessionId, resolvedEvent, outputFile, resolvedFormat,
            resolvedDuration, resolvedInterval, Instant.now(), stateCleanup);

        return StartResult.started(sessionId, resolvedEvent, resolvedFormat,
            resolvedDuration, resolvedInterval, outputFile.toString());
    }

    /**
     * Stops the active profiling session.
     *
     * @return result describing the stopped session, or an error if no session is active
     */
    public synchronized StopResult stop() {
        if (!profilerAvailable) {
            return StopResult.unavailable(profilerLoadError);
        }
        if (activeSession == null) {
            return StopResult.notRunning();
        }
        return doStop(false);
    }

    /**
     * Returns the current profiler status without modifying any session.
     */
    public synchronized ProfilerStatus status() {
        if (!profilerAvailable) {
            return ProfilerStatus.unavailable(profilerLoadError);
        }
        if (activeSession != null) {
            return ProfilerStatus.running(activeSession);
        }
        return ProfilerStatus.idle(lastCompleted);
    }

    /**
     * Returns a flat text profile showing the top-N hottest methods by sample count.
     * Can be called while a session is active (live snapshot) or after it has stopped.
     *
     * @param maxMethods maximum number of methods to include; if {@code null} the
     *                   configured default ({@code bootlens.client.profiler.dump-flat-max-methods}) is used
     */
    public DumpResult dumpFlat(@Nullable Integer maxMethods) {
        if (!profilerAvailable) {
            return DumpResult.unavailable(profilerLoadError);
        }
        int limit = maxMethods != null ? maxMethods : properties.getDumpFlatMaxMethods();
        try {
            String data = AsyncProfiler.getInstance().dumpFlat(limit);
            return DumpResult.ok("flat", data);
        }
        catch (Exception ex) {
            return DumpResult.failed(ex.getMessage());
        }
    }

    /**
     * Returns the top-N call traces (stack-trace samples) as plain text.
     * Can be called while a session is active (live snapshot) or after it has stopped.
     *
     * @param maxTraces maximum number of traces to include; if {@code null} the
     *                  configured default ({@code bootlens.client.profiler.dump-traces-max-traces}) is used
     */
    public DumpResult dumpTraces(@Nullable Integer maxTraces) {
        if (!profilerAvailable) {
            return DumpResult.unavailable(profilerLoadError);
        }
        int limit = maxTraces != null ? maxTraces : properties.getDumpTracesMaxTraces();
        try {
            String data = AsyncProfiler.getInstance().dumpTraces(limit);
            return DumpResult.ok("traces", data);
        }
        catch (Exception ex) {
            return DumpResult.failed(ex.getMessage());
        }
    }

    /**
     * Returns collapsed stacks in-memory (no file written).
     * Compatible with the <a href="https://github.com/brendangregg/FlameGraph">FlameGraph</a> tool.
     * Can be called while a session is active (live snapshot) or after it has stopped.
     */
    public DumpResult dumpCollapsed() {
        if (!profilerAvailable) {
            return DumpResult.unavailable(profilerLoadError);
        }
        try {
            String data = AsyncProfiler.getInstance().execute("collapsed");
            return DumpResult.ok("collapsed", data);
        }
        catch (Exception ex) {
            return DumpResult.failed(ex.getMessage());
        }
    }

    /**
     * Returns the number of profiling samples collected so far.
     * Useful for monitoring collection progress during a running session.
     */
    public SamplesResult getSamples() {
        if (!profilerAvailable) {
            return new SamplesResult(false, -1L, profilerLoadError);
        }
        try {
            long samples = AsyncProfiler.getInstance().getSamples();
            return new SamplesResult(true, samples, null);
        }
        catch (Exception ex) {
            return new SamplesResult(false, -1L, ex.getMessage());
        }
    }

    /**
     * Returns the version string of the loaded async-profiler native library.
     */
    public VersionResult getVersion() {
        if (!profilerAvailable) {
            return new VersionResult(false, null, profilerLoadError);
        }
        try {
            String version = AsyncProfiler.getInstance().getVersion();
            return new VersionResult(true, version, null);
        }
        catch (Exception ex) {
            return new VersionResult(false, null, ex.getMessage());
        }
    }

    /**
     * Returns the output {@link Path} for a given filename if it exists inside
     * the configured output directory (prevents path traversal).
     */
    public Path resolveOutputFile(String filename) throws SecurityException {
        Path base = Paths.get(properties.getOutputDir()).toAbsolutePath().normalize();
        Path target = base.resolve(filename).normalize();
        if (!target.startsWith(base)) {
            throw new SecurityException("Path traversal detected: " + filename);
        }
        return target;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void autoStop() {
        synchronized (this) {
            if (activeSession != null) {
                doStop(true);
            }
        }
    }

    private StopResult doStop(boolean automatic) {
        ActiveSession session = activeSession;
        activeSession = null;

        // Cancel the state-cleanup future when stopping manually
        if (!automatic && session.stateCleanupFuture() != null) {
            session.stateCleanupFuture().cancel(false);
        }

        try {
            // async-profiler may have already stopped itself via timeout=; calling
            // stop again is harmless — it simply writes any buffered data and returns.
            AsyncProfiler.getInstance().execute("stop");
        }
        catch (Exception ex) {
            lastCompleted = new CompletedSession(session.sessionId(), session.event(),
                session.outputFile(), session.format(), session.startedAt(), Instant.now(),
                false, ex.getMessage());
            return StopResult.failed(ex.getMessage());
        }

        lastCompleted = new CompletedSession(session.sessionId(), session.event(),
            session.outputFile(), session.format(), session.startedAt(), Instant.now(),
            true, null);
        return StopResult.stopped(session.sessionId(), session.outputFile().toString(),
            session.format(), automatic);
    }

    private Duration clampDuration(Duration requested) {
        Duration max = properties.getMaxDuration();
        return requested.compareTo(max) > 0 ? max : requested;
    }

    private Path ensureOutputDir() {
        Path dir = Paths.get(properties.getOutputDir());
        try {
            Files.createDirectories(dir);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Cannot create profiler output directory: " + dir, ex);
        }
        return dir;
    }

    /**
     * Builds the async-profiler {@code execute} command string.
     *
     * <p>Uses native {@code timeout=} so async-profiler auto-stops the collection
     * even if the JVM scheduler is delayed. Our own scheduler additionally updates
     * the internal state ({@link #activeSession}).
     */
    private static String buildStartCommand(
        String event,
        Path outputFile,
        AsyncProfilerProperties.OutputFormat format,
        @Nullable String interval,
        Duration duration,
        int jstackdepth,
        boolean threads
    ) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("start");
        cmd.append(",event=").append(event);
        cmd.append(",output=").append(format.getCommandValue());
        cmd.append(",file=").append(outputFile.toAbsolutePath());
        cmd.append(",timeout=").append(duration.toSeconds());

        if (interval != null && !interval.isBlank()) {
            cmd.append(",interval=").append(interval);
        }
        if (jstackdepth > 0) {
            cmd.append(",jstackdepth=").append(jstackdepth);
        }
        if (threads) {
            cmd.append(",threads");
        }

        return cmd.toString();
    }

    // -------------------------------------------------------------------------
    // Internal state records
    // -------------------------------------------------------------------------

    private record ActiveSession(
        String sessionId,
        String event,
        Path outputFile,
        AsyncProfilerProperties.OutputFormat format,
        Duration requestedDuration,
        @Nullable String interval,
        Instant startedAt,
        @Nullable ScheduledFuture<?> stateCleanupFuture
    ) {}

    record CompletedSession(
        String sessionId,
        String event,
        Path outputFile,
        AsyncProfilerProperties.OutputFormat format,
        Instant startedAt,
        Instant completedAt,
        boolean success,
        @Nullable String errorMessage
    ) {}

    // -------------------------------------------------------------------------
    // Result / status types (returned from public methods)
    // -------------------------------------------------------------------------

    public record StartResult(
        String status,
        @Nullable String sessionId,
        @Nullable String event,
        @Nullable String format,
        @Nullable Long durationSeconds,
        @Nullable String interval,
        @Nullable String outputFile,
        @Nullable String message
    ) {
        static StartResult started(String id, String event,
            AsyncProfilerProperties.OutputFormat format,
            Duration duration, @Nullable String interval, String file) {
            return new StartResult("STARTED", id, event,
                format.name().toLowerCase(), duration.toSeconds(), interval, file, null);
        }
        static StartResult alreadyRunning(String id) {
            return new StartResult("ALREADY_RUNNING", id,
                null, null, null, null, null, "A profiling session is already active.");
        }
        static StartResult unavailable(String error) {
            return new StartResult("UNAVAILABLE", null,
                null, null, null, null, null, "async-profiler is not available: " + error);
        }
        static StartResult failed(String error) {
            return new StartResult("FAILED", null,
                null, null, null, null, null, "Failed to start profiling: " + error);
        }
    }

    public record StopResult(
        String status,
        @Nullable String sessionId,
        @Nullable String outputFile,
        @Nullable String format,
        boolean automatic,
        @Nullable String message
    ) {
        static StopResult stopped(String id, String file,
            AsyncProfilerProperties.OutputFormat format, boolean automatic) {
            return new StopResult("STOPPED", id, file,
                format.name().toLowerCase(), automatic, null);
        }
        static StopResult notRunning() {
            return new StopResult("NOT_RUNNING", null,
                null, null, false, "No active profiling session.");
        }
        static StopResult unavailable(String error) {
            return new StopResult("UNAVAILABLE", null,
                null, null, false, "async-profiler is not available: " + error);
        }
        static StopResult failed(String error) {
            return new StopResult("FAILED", null,
                null, null, false, "Failed to stop profiling: " + error);
        }
    }

    public record DumpResult(
        String status,
        @Nullable String type,
        @Nullable String data,
        @Nullable String message
    ) {
        static DumpResult ok(String type, String data) {
            return new DumpResult("OK", type, data, null);
        }
        static DumpResult unavailable(String error) {
            return new DumpResult("UNAVAILABLE", null, null,
                "async-profiler is not available: " + error);
        }
        static DumpResult failed(String error) {
            return new DumpResult("FAILED", null, null, error);
        }
    }

    public record SamplesResult(
        boolean available,
        long samples,
        @Nullable String errorMessage
    ) {}

    public record VersionResult(
        boolean available,
        @Nullable String version,
        @Nullable String errorMessage
    ) {}

    public record ProfilerStatus(
        String state,
        boolean profilerAvailable,
        @Nullable String profilerLoadError,
        @Nullable String activeSessionId,
        @Nullable String activeEvent,
        @Nullable String activeFormat,
        @Nullable String activeInterval,
        @Nullable Instant activeStartedAt,
        @Nullable Long activeRemainingSeconds,
        @Nullable String lastSessionId,
        @Nullable String lastOutputFile,
        @Nullable String lastFormat,
        @Nullable Instant lastCompletedAt,
        @Nullable Boolean lastSessionSucceeded
    ) {
        static ProfilerStatus running(ActiveSession session) {
            long elapsedMs = Duration.between(session.startedAt(), Instant.now()).toMillis();
            long remainingMs = session.requestedDuration().toMillis() - elapsedMs;
            return new ProfilerStatus("RUNNING", true, null,
                session.sessionId(), session.event(),
                session.format().name().toLowerCase(), session.interval(),
                session.startedAt(), Math.max(0, remainingMs / 1000),
                null, null, null, null, null);
        }
        static ProfilerStatus idle(@Nullable CompletedSession last) {
            if (last == null) {
                return new ProfilerStatus("IDLE", true, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null);
            }
            return new ProfilerStatus("IDLE", true, null,
                null, null, null, null, null, null,
                last.sessionId(), last.outputFile().toString(),
                last.format().name().toLowerCase(), last.completedAt(), last.success());
        }
        static ProfilerStatus unavailable(@Nullable String error) {
            return new ProfilerStatus("UNAVAILABLE", false, error,
                null, null, null, null, null, null,
                null, null, null, null, null);
        }
    }
}
