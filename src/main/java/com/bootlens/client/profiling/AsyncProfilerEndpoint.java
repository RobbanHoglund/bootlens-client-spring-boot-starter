package com.bootlens.client.profiling;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

/**
 * Spring Boot actuator endpoint that exposes the embedded async-profiler.
 *
 * <ul>
 *   <li>{@code GET  /actuator/bootlensProfiler} — current profiler status</li>
 *   <li>{@code GET  /actuator/bootlensProfiler/{operation}} — in-memory dump or query</li>
 *   <li>{@code POST /actuator/bootlensProfiler} — start a profiling session</li>
 *   <li>{@code DELETE /actuator/bootlensProfiler} — stop the active session</li>
 * </ul>
 *
 * <p>File download support is provided by {@link AsyncProfilerWebExtension}, which is
 * activated only in Servlet web contexts and extends this endpoint with the
 * {@code GET /actuator/bootlensProfiler/download/{filename}} operation.
 */
@Endpoint(id = "bootlensProfiler")
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
    public Object query(@Selector String operation, @Nullable Integer limit) {
        return switch (operation.toLowerCase()) {
            case "flat"      -> service.dumpFlat(limit);
            case "traces"    -> service.dumpTraces(limit);
            case "collapsed" -> service.dumpCollapsed();
            case "samples"   -> service.getSamples();
            case "version"   -> service.getVersion();
            default -> Map.of(
                "message", "Unknown operation '" + operation + "'. "
                    + "Supported: flat, traces, collapsed, samples, version");
        };
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
     *                        alloc/nativemem: allocation size between samples (e.g. {@code "1m"});
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
}
