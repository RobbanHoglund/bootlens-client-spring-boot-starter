package com.bootlens.client.profiling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web fallback for the BootLens profiler actuator surface.
 *
 * <p>Spring Boot's generic web endpoint extension model is intentionally
 * conservative. Keeping the profiler HTTP surface as a normal controller makes
 * status/start/stop/download predictable while still living under the actuator
 * base path and honoring the same on-demand safety model.
 */
@RestController
@RequestMapping("${management.endpoints.web.base-path:/actuator}/bootlensProfiler")
public class AsyncProfilerController {

    private final AsyncProfilerService service;

    public AsyncProfilerController(AsyncProfilerService service) {
        this.service = service;
    }

    @GetMapping
    public AsyncProfilerService.ProfilerStatus getStatus() {
        return service.status();
    }

    @GetMapping("/{operation:flat|traces|collapsed|samples|version}")
    public Object query(@PathVariable String operation, @Nullable Integer limit) {
        return switch (operation.toLowerCase()) {
            case "flat"      -> service.dumpFlat(limit);
            case "traces"    -> service.dumpTraces(limit);
            case "collapsed" -> service.dumpCollapsed();
            case "samples"   -> service.getSamples();
            case "version"   -> service.getVersion();
            default -> Map.of("message", "Unknown profiler operation: " + operation);
        };
    }

    @PostMapping
    public AsyncProfilerService.StartResult startProfiling(
        @RequestBody(required = false) Map<String, Object> request
    ) {
        String event = stringValue(request, "event");
        Long durationSeconds = longValue(request, "durationSeconds");
        String format = stringValue(request, "format");
        String interval = stringValue(request, "interval");
        Boolean inverted = booleanValue(request, "inverted");
        Duration duration = durationSeconds != null ? Duration.ofSeconds(durationSeconds) : null;
        AsyncProfilerProperties.OutputFormat outputFormat =
            format != null ? AsyncProfilerProperties.OutputFormat.fromString(format) : null;
        return service.start(event, duration, outputFormat, interval, inverted);
    }

    @DeleteMapping
    public AsyncProfilerService.StopResult stopProfiling() {
        return service.stop();
    }

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<?> download(@PathVariable String filename) {
        if (filename == null || filename.contains("/") || filename.contains("\\")
            || filename.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid filename."));
        }

        Path file;
        try {
            file = service.resolveOutputFile(filename);
        }
        catch (SecurityException ex) {
            return ResponseEntity.status(403).body(Map.of("message", "Access denied: " + ex.getMessage()));
        }

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return ResponseEntity.status(404).body(Map.of("message", "Profiling output file not found: " + filename));
        }

        try {
            long fileSize = Files.size(file);
            if (fileSize > ProfilerConstants.MAX_OUTPUT_BYTES) {
                return ResponseEntity.status(500).body(Map.of(
                    "message",
                    "Profiling output file is too large to serve: "
                        + (fileSize / (1024 * 1024)) + " MB (limit "
                        + (ProfilerConstants.MAX_OUTPUT_BYTES / (1024 * 1024)) + " MB)"));
            }
        }
        catch (IOException ex) {
            return ResponseEntity.status(500).body(Map.of(
                "message", "Could not read profiling output file: " + ex.getMessage()));
        }

        return ResponseEntity.ok()
            .contentType(resolveMediaType(filename))
            .body(new FileSystemResource(file));
    }

    private static MediaType resolveMediaType(String filename) {
        if (filename.endsWith(".html")) {
            return MediaType.TEXT_HTML;
        }
        if (filename.endsWith(".jfr")) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return MediaType.TEXT_PLAIN;
    }

    private static String stringValue(@Nullable Map<String, Object> request, String key) {
        if (request == null) {
            return null;
        }
        Object value = request.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private static Long longValue(@Nullable Map<String, Object> request, String key) {
        if (request == null) {
            return null;
        }
        Object value = request.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    @Nullable
    private static Boolean booleanValue(@Nullable Map<String, Object> request, String key) {
        if (request == null) {
            return null;
        }
        Object value = request.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
