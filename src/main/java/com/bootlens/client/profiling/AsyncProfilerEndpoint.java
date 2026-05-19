package com.bootlens.client.profiling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.lang.Nullable;
import org.springframework.util.MimeType;

/**
 * Spring Boot actuator endpoint that exposes the embedded async-profiler.
 *
 * <ul>
 *   <li>{@code GET  /actuator/bootlensProfiler} — current profiler status</li>
 *   <li>{@code POST /actuator/bootlensProfiler} — start a profiling session</li>
 *   <li>{@code DELETE /actuator/bootlensProfiler} — stop the active session</li>
 * </ul>
 *
 * <p>Download support is exposed by the same web endpoint at
 * {@code /actuator/bootlensProfiler/download/{filename}}.
 */
public class AsyncProfilerEndpoint {

    private final AsyncProfilerService service;

    public AsyncProfilerEndpoint(AsyncProfilerService service) {
        this.service = service;
    }

    /**
     * Returns the current profiler status without modifying any session.
     */
    @ReadOperation
    public AsyncProfilerService.ProfilerStatus getStatus() {
        return service.status();
    }

    /**
     * In-memory data operations — available during a running session (live snapshot)
     * or after it has stopped.
     *
     * <ul>
     *   <li>{@code flat}      → top-N hottest methods by sample count (plain text)</li>
     *   <li>{@code traces}    → top-N call traces (plain text)</li>
     *   <li>{@code collapsed} → collapsed stacks, compatible with FlameGraph tooling</li>
     *   <li>{@code samples}   → total sample count collected so far</li>
     *   <li>{@code version}   → async-profiler native library version</li>
     * </ul>
     *
     * <p>The {@code flat} and {@code traces} operations accept an optional
     * {@code limit} parameter to override the configured default.
     */
    @ReadOperation
    public Object query(@Selector String operation) {
        return switch (operation.toLowerCase()) {
            case "flat"      -> service.dumpFlat(null);
            case "traces"    -> service.dumpTraces(null);
            case "collapsed" -> service.dumpCollapsed();
            case "samples"   -> service.getSamples();
            case "version"   -> service.getVersion();
            default -> Map.of(
                "message", "Unknown operation '" + operation + "'. "
                    + "Supported: flat, traces, collapsed, samples, version");
        };
    }

    /**
     * Downloads a profiling result file.
     */
    @ReadOperation
    public WebEndpointResponse<?> download(@Selector String section, @Selector String filename) {
        if (!"download".equalsIgnoreCase(section)) {
            return new WebEndpointResponse<>(
                Map.of("message", "Use /actuator/bootlensProfiler/download/{filename}"), 404);
        }

        if (filename == null || filename.contains("/") || filename.contains("\\")
            || filename.contains("..")) {
            return new WebEndpointResponse<>(
                Map.of("message", "Invalid filename."), 400);
        }

        Path file;
        try {
            file = service.resolveOutputFile(filename);
        }
        catch (SecurityException ex) {
            return new WebEndpointResponse<>(
                Map.of("message", "Access denied: " + ex.getMessage()), 403);
        }

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return new WebEndpointResponse<>(
                Map.of("message", "Profiling output file not found: " + filename), 404);
        }

        try {
            long fileSize = Files.size(file);
            if (fileSize > ProfilerConstants.MAX_OUTPUT_BYTES) {
                return new WebEndpointResponse<>(
                    Map.of("message", "Profiling output file is too large to serve: "
                        + (fileSize / (1024 * 1024)) + " MB (limit "
                        + (ProfilerConstants.MAX_OUTPUT_BYTES / (1024 * 1024)) + " MB)"), 500);
            }
        }
        catch (IOException ex) {
            return new WebEndpointResponse<>(
                Map.of("message", "Could not read profiling output file: " + ex.getMessage()), 500);
        }

        MimeType mimeType = resolveMimeType(filename);
        return new WebEndpointResponse<>(new FileSystemResource(file), 200, mimeType);
    }

    /**
     * Starts a new profiling session.
     *
     * @param event           profiling event: {@code cpu}, {@code alloc}, {@code wall}, {@code lock},
     *                        {@code nativemem}, {@code cache-misses}, etc.
     *                        (optional — defaults to {@code bootlens.client.profiler.default-event})
     * @param durationSeconds session length in seconds
     *                        (optional — defaults to {@code bootlens.client.profiler.default-duration})
     * @param format          output format: {@code flamegraph}, {@code jfr}, {@code tree}, {@code collapsed}
     *                        (optional — defaults to {@code bootlens.client.profiler.default-format})
     * @param interval        sampling interval — meaning depends on event type:
     *                        cpu/wall/ctimer: time between samples (e.g. {@code "2ms"}, {@code "500us"});
     *                        alloc/nativemem: allocation size between samples (e.g. {@code "512k"});
     *                        lock: lock-wait threshold (e.g. {@code "5ms"}).
     *                        Accepts the same unit suffixes as async-profiler: ns, us, ms, s, k, m, g.
     *                        (optional — defaults to a sensible per-event constant from {@link ProfilerConstants})
     * @param inverted        when {@code true}, render flamegraph output as icicle/top-down layout.
     */
    @WriteOperation
    public AsyncProfilerService.StartResult startProfiling(
        @Nullable String event,
        @Nullable Long durationSeconds,
        @Nullable String format,
        @Nullable String interval,
        @Nullable Boolean inverted
    ) {
        Duration duration = durationSeconds != null ? Duration.ofSeconds(durationSeconds) : null;
        AsyncProfilerProperties.OutputFormat outputFormat =
            format != null ? AsyncProfilerProperties.OutputFormat.fromString(format) : null;
        return service.start(event, duration, outputFormat, interval, inverted);
    }

    /**
     * Stops the active profiling session early. The output file is still
     * written with whatever data was collected.
     */
    @DeleteOperation
    public AsyncProfilerService.StopResult stopProfiling() {
        return service.stop();
    }

    private static MimeType resolveMimeType(String filename) {
        if (filename.endsWith(".html")) {
            return MimeType.valueOf("text/html");
        }
        if (filename.endsWith(".jfr")) {
            return MimeType.valueOf("application/octet-stream");
        }
        return MimeType.valueOf("text/plain");
    }
}
