package com.bootlens.client.profiling;

import java.time.Duration;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

/**
 * Spring Boot actuator endpoint that exposes the embedded async-profiler.
 *
 * <ul>
 *   <li>{@code GET  /actuator/bootlensProfiler} — current profiler status</li>
 *   <li>{@code POST /actuator/bootlensProfiler} — start a profiling session</li>
 *   <li>{@code DELETE /actuator/bootlensProfiler} — stop the active session</li>
 * </ul>
 *
 * <p>Downloading the result file is handled by {@link AsyncProfilerWebExtension}
 * (servlet environments only).
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
     * Starts a new profiling session.
     *
     * @param event           profiling event: {@code cpu}, {@code alloc}, {@code wall}, {@code lock}
     *                        (optional — defaults to {@code bootlens.client.profiler.default-event})
     * @param durationSeconds session length in seconds
     *                        (optional — defaults to {@code bootlens.client.profiler.default-duration})
     * @param format          output format: {@code flamegraph}, {@code jfr}, {@code tree}, {@code collapsed}
     *                        (optional — defaults to {@code bootlens.client.profiler.default-format})
     */
    @WriteOperation
    public AsyncProfilerService.StartResult startProfiling(
        @Nullable String event,
        @Nullable Long durationSeconds,
        @Nullable String format
    ) {
        Duration duration = durationSeconds != null ? Duration.ofSeconds(durationSeconds) : null;
        AsyncProfilerProperties.OutputFormat outputFormat =
            format != null ? AsyncProfilerProperties.OutputFormat.fromString(format) : null;
        return service.start(event, duration, outputFormat);
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
