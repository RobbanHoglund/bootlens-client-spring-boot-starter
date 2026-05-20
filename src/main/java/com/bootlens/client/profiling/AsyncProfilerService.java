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
import java.util.regex.Pattern;

import one.profiler.AsyncProfiler;
import one.profiler.AsyncProfilerLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * Service that manages embedded async-profiler sessions.
 *
 * <p>Only one profiling session can be active at a time per JVM. All public
 * methods are {@code synchronized} to guarantee mutual exclusion.
 *
 * <p>The async-profiler command receives a native {@code timeout=N} so the
 * profiler stops its own collection when time is up. A backup scheduler
 * additionally cleans up the {@link #activeSession} state after that time
 * so that {@link #status()} correctly transitions from RUNNING to IDLE.
 *
 * <p>When no explicit {@code interval} is provided, sensible per-event defaults
 * from {@link ProfilerConstants} are used:
 * <ul>
 *   <li>cpu / ctimer — {@value ProfilerConstants#CPU_INTERVAL} /
 *       {@value ProfilerConstants#CTIMER_INTERVAL}</li>
 *   <li>wall — {@value ProfilerConstants#WALL_INTERVAL}</li>
 *   <li>alloc / nativemem — {@value ProfilerConstants#ALLOC_THRESHOLD}</li>
 *   <li>lock — {@value ProfilerConstants#LOCK_THRESHOLD}</li>
 * </ul>
 */
public class AsyncProfilerService {

    private static final Logger log = LoggerFactory.getLogger(AsyncProfilerService.class);
    private static final Pattern COMMAND_TOKEN = Pattern.compile("[A-Za-z0-9._:-]+");
    static final String AP_LOADER_EXTRACTION_DIR_PROPERTY = "ap_loader_extraction_dir";
    private static final String DEFAULT_AP_LOADER_EXTRACTION_DIR = "bootlens-ap-loader";

    private final AsyncProfilerProperties properties;
    private final ScheduledExecutorService scheduler;

    private final boolean profilerAvailable;
    @Nullable private final String profilerLoadError;
    @Nullable private final AsyncProfiler profiler;

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
        AsyncProfiler loadedProfiler = null;
        Path extractionDir = configureApLoaderExtractionDirectory();
        try {
            AsyncProfiler profiler = AsyncProfilerLoader.load();
            loadedProfiler = profiler;
            available = true;
            log.info("async-profiler loaded, version: {}, extractionDir={}",
                profiler.getVersion(), extractionDir != null ? extractionDir : "(custom or unavailable)");
        }
        catch (UnsatisfiedLinkError | Exception ex) {
            loadError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.debug("async-profiler is not available on this host: {}", loadError);
        }
        this.profilerAvailable = available;
        this.profilerLoadError = loadError;
        this.profiler = loadedProfiler;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts a new profiling session.
     *
     * <p>The {@code interval} parameter meaning depends on the event type:
     * <ul>
     *   <li><b>cpu / wall / ctimer</b> — time between samples, e.g. {@code "2ms"} or
     *       {@code "1000000"} (plain numbers are treated as nanoseconds).</li>
     *   <li><b>alloc / nativemem / nativememleak</b> — heap/native allocation size
     *       between samples, e.g. {@code "1m"} or {@code "2m"}.</li>
     *   <li><b>lock</b> — minimum lock-wait threshold, e.g. {@code "5ms"}.</li>
     * </ul>
     * Accepts the same unit suffixes as async-profiler: {@code ns}, {@code us},
     * {@code ms}, {@code s}, {@code k}, {@code m}, {@code g}.
     * Pass {@code null} to use per-event defaults from {@link ProfilerConstants}.
     *
     * @param event    profiling event (cpu, alloc, wall, lock, ctimer, nativemem,
     *                 nativememleak); accepts case/hyphen variants; null → default
     * @param duration profiling duration; null → default
     * @param format   output format; null → default
     * @param interval sampling interval / threshold; null → per-event default
     * @return result describing the started session
     */
    public synchronized StartResult start(
        @Nullable String event,
        @Nullable Duration duration,
        @Nullable AsyncProfilerProperties.OutputFormat format,
        @Nullable String interval
    ) {
        return start(event, duration, format, interval, null);
    }

    public synchronized StartResult start(
        @Nullable String event,
        @Nullable Duration duration,
        @Nullable AsyncProfilerProperties.OutputFormat format,
        @Nullable String interval,
        @Nullable Boolean inverted
    ) {
        if (!profilerAvailable) {
            return StartResult.unavailable(profilerLoadError);
        }
        if (activeSession != null) {
            return StartResult.alreadyRunning(activeSession.sessionId());
        }

        String rawEvent = firstNonBlank(event, properties.getDefaultEvent());
        if (rawEvent == null) {
            return StartResult.failed("Profiling event must not be blank.");
        }
        ProfilerEvent resolvedEvent = ProfilerEvent.fromString(rawEvent)
            .orElse(null); // unknown safe tokens pass through as free-form strings (e.g. cache-misses, cycles)

        String canonicalEvent = resolvedEvent != null ? resolvedEvent.externalName() : rawEvent.trim();
        String invalidEventMessage = validateCommandToken("event", canonicalEvent);
        if (invalidEventMessage != null) {
            return StartResult.failed(invalidEventMessage);
        }
        Duration resolvedDuration = clampDuration(duration != null ? duration : properties.getDefaultDuration());
        AsyncProfilerProperties.OutputFormat resolvedFormat = format != null ? format : properties.getDefaultFormat();
        String resolvedInterval = firstNonBlank(interval, defaultIntervalFor(resolvedEvent));
        String invalidIntervalMessage = validateOptionalCommandToken("interval", resolvedInterval);
        if (invalidIntervalMessage != null) {
            return StartResult.failed(invalidIntervalMessage);
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path outputDir = ensureOutputDir();
        String fileName = "bootlens-" + sessionId + resolvedFormat.getFileExtension();
        Path outputFile = outputDir.resolve(fileName);

        String command;
        try {
            command = buildStartCommand(resolvedEvent, canonicalEvent, outputFile, resolvedFormat,
                resolvedInterval, resolvedDuration, properties.getJstackdepth(), properties.isThreads(),
                Boolean.TRUE.equals(inverted));
        }
        catch (IllegalArgumentException ex) {
            return StartResult.failed(ex.getMessage());
        }

        log.debug("Starting async-profiler session {}: {}", sessionId, command);
        try {
            profiler().execute(command);
        }
        catch (Exception ex) {
            log.warn("Failed to start async-profiler session {}: {}", sessionId, ex.getMessage());
            return StartResult.failed(ex.getMessage());
        }
        log.info("async-profiler session {} started — event={}, duration={}s, interval={}",
            sessionId, canonicalEvent, resolvedDuration.toSeconds(), resolvedInterval);

        // Backup scheduler: async-profiler stops itself via timeout= but we need to
        // update internal state (activeSession → lastCompleted) when time is up.
        // Add a small grace period so async-profiler's own stop runs first.
        ScheduledFuture<?> stateCleanup = scheduler.schedule(
            this::autoStop,
            resolvedDuration.toMillis() + 1_000L,
            TimeUnit.MILLISECONDS);

        activeSession = new ActiveSession(sessionId, canonicalEvent, outputFile, resolvedFormat,
            resolvedDuration, resolvedInterval, Instant.now(), stateCleanup);

        return StartResult.started(sessionId, canonicalEvent, resolvedFormat,
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
            String data = profiler().dumpFlat(limit);
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
            String data = profiler().dumpTraces(limit);
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
            String data = profiler().execute("collapsed");
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
            long samples = profiler().getSamples();
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
            String version = profiler().getVersion();
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

        if (!automatic && session.stateCleanupFuture() != null) {
            session.stateCleanupFuture().cancel(false);
        }

        if (automatic) {
            // async-profiler stopped itself via timeout= and already wrote the output file.
            // Calling execute("stop") again would throw IllegalStateException ("Profiler is
            // not started"). Determine success by checking whether the output was actually written.
            boolean fileOk = session.outputFile().toFile().length() > 0;
            String errorMessage = fileOk ? null : "Output file was not written";
            log.info("async-profiler session {} timed out — output {}: {}",
                session.sessionId(), fileOk ? "written" : "missing", session.outputFile());
            lastCompleted = new CompletedSession(session.sessionId(), session.event(),
                session.outputFile(), session.format(), session.startedAt(), Instant.now(),
                fileOk, errorMessage);
            return StopResult.stopped(session.sessionId(), session.outputFile().toString(),
                session.format(), true);
        }

        // Manual stop — tell async-profiler to flush and write the output file now.
        try {
            profiler().execute("stop");
            log.info("async-profiler session {} stopped manually — output: {}",
                session.sessionId(), session.outputFile());
        }
        catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("Failed to stop async-profiler session {}: {}", session.sessionId(), msg);
            lastCompleted = new CompletedSession(session.sessionId(), session.event(),
                session.outputFile(), session.format(), session.startedAt(), Instant.now(),
                false, msg);
            return StopResult.failed(msg);
        }

        lastCompleted = new CompletedSession(session.sessionId(), session.event(),
            session.outputFile(), session.format(), session.startedAt(), Instant.now(),
            true, null);
        return StopResult.stopped(session.sessionId(), session.outputFile().toString(),
            session.format(), false);
    }

    private AsyncProfiler profiler() {
        if (profiler == null) {
            String message = profilerLoadError != null && !profilerLoadError.isBlank()
                ? profilerLoadError
                : "async-profiler is not available.";
            throw new IllegalStateException(message);
        }
        return profiler;
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
     * Returns the sensible per-event default interval when none is provided.
     * Returns {@code null} for free-form events (e.g. {@code cache-misses}) where
     * no typed mapping exists and async-profiler's built-in default applies.
     */
    @Nullable
    private static String defaultIntervalFor(@Nullable ProfilerEvent event) {
        if (event == null) return null;
        return switch (event) {
            case CPU             -> ProfilerConstants.CPU_INTERVAL;
            case WALL            -> ProfilerConstants.WALL_INTERVAL;
            case CTIMER          -> ProfilerConstants.CTIMER_INTERVAL;
            case ALLOC           -> ProfilerConstants.ALLOC_THRESHOLD;
            case LOCK            -> ProfilerConstants.LOCK_THRESHOLD;
            case NATIVE_MEM,
                 NATIVE_MEM_LEAK -> ProfilerConstants.NATIVEMEM_THRESHOLD;
        };
    }

    /**
     * Builds the async-profiler {@code execute} command string.
     *
     * <p>The interval/threshold option key depends on the event type:
     * <ul>
     *   <li>cpu/wall/ctimer — {@code interval=}</li>
     *   <li>alloc — {@code alloc=}</li>
     *   <li>lock — {@code lock=}</li>
     *   <li>nativemem/nativememleak — {@code nativemem=}</li>
     *   <li>unknown/free-form — {@code interval=} (best effort)</li>
     * </ul>
     */
    static String buildStartCommand(
        @Nullable ProfilerEvent event,
        String canonicalEventName,
        Path outputFile,
        AsyncProfilerProperties.OutputFormat format,
        @Nullable String interval,
        Duration duration,
        int jstackdepth,
        boolean threads,
        boolean inverted
    ) {
        String absPath = outputFile.toAbsolutePath().normalize().toString();
        if (absPath.contains(",")) {
            throw new IllegalArgumentException(
                "Output file path must not contain commas: " + absPath);
        }

        // For NATIVE_MEM_LEAK the external event name in the command is still "nativemem"
        String cmdEvent = (event == ProfilerEvent.NATIVE_MEM_LEAK)
            ? ProfilerEvent.NATIVE_MEM.externalName()
            : canonicalEventName;

        StringBuilder cmd = new StringBuilder();
        cmd.append("start");
        cmd.append(",event=").append(cmdEvent);
        cmd.append(",").append(format.getCommandValue());
        cmd.append(",file=").append(absPath);
        cmd.append(",timeout=").append(duration.toSeconds());

        if (interval != null && !interval.isBlank()) {
            appendIntervalOption(cmd, event, interval);
        }

        if (jstackdepth > 0) {
            cmd.append(",jstackdepth=").append(jstackdepth);
        }
        if (threads) {
            cmd.append(",threads");
        }
        if (inverted && format == AsyncProfilerProperties.OutputFormat.FLAMEGRAPH) {
            cmd.append(",inverted");
        }

        // Native memory variants require extra flags
        if (event == ProfilerEvent.NATIVE_MEM) {
            cmd.append(",nofree");
        }
        else if (event == ProfilerEvent.NATIVE_MEM_LEAK) {
            cmd.append(",leak");
        }

        return cmd.toString();
    }

    /**
     * Appends the correct async-profiler option key for the given event type.
     * Each event uses a different key because the semantics of "interval" differ.
     */
    private static void appendIntervalOption(
        StringBuilder cmd, @Nullable ProfilerEvent event, String value
    ) {
        if (event == null) {
            // Free-form event (e.g. cache-misses, cycles) — best-effort interval
            cmd.append(",interval=").append(value);
            return;
        }
        switch (event) {
            case CPU, WALL, CTIMER            -> cmd.append(",interval=").append(value);
            case ALLOC                        -> cmd.append(",alloc=").append(value);
            case LOCK                         -> cmd.append(",lock=").append(value);
            case NATIVE_MEM, NATIVE_MEM_LEAK -> cmd.append(",nativemem=").append(value);
        }
    }

    static String validateOptionalCommandToken(String label, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return validateCommandToken(label, value);
    }

    static String validateCommandToken(String label, String value) {
        if (value == null || value.isBlank()) {
            return "Profiling " + label + " must not be blank.";
        }
        if (!COMMAND_TOKEN.matcher(value).matches()) {
            return "Profiling " + label
                + " contains unsupported characters; use only letters, digits, '.', '_', ':' or '-'.";
        }
        return null;
    }

    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    static @Nullable Path configureApLoaderExtractionDirectory() {
        String configured = System.getProperty(AP_LOADER_EXTRACTION_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }

        String tmpDir = System.getProperty("java.io.tmpdir");
        if (tmpDir == null || tmpDir.isBlank()) {
            return null;
        }

        Path extractionDir = Paths.get(tmpDir, DEFAULT_AP_LOADER_EXTRACTION_DIR)
            .toAbsolutePath()
            .normalize();
        try {
            Files.createDirectories(extractionDir);
            System.setProperty(AP_LOADER_EXTRACTION_DIR_PROPERTY, extractionDir.toString());
            return extractionDir;
        }
        catch (IOException | RuntimeException ex) {
            log.debug("Could not prepare async-profiler extraction directory {}: {}",
                extractionDir, ex.getMessage());
            return null;
        }
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
