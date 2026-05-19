package com.bootlens.client.profiling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.annotation.EndpointWebExtension;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeType;

/**
 * Servlet web extension for {@link AsyncProfilerEndpoint} that adds a file
 * download operation not available through the standard JSON endpoint.
 *
 * <p>Use {@code GET /actuator/bootlensProfiler/download/{filename}} to retrieve
 * a completed profiling result. The filename is reported in the
 * {@code outputFile} field of the stop/status responses.
 */
@EndpointWebExtension(endpoint = AsyncProfilerEndpoint.class)
public class AsyncProfilerWebExtension {

    private final AsyncProfilerService service;

    public AsyncProfilerWebExtension(AsyncProfilerService service) {
        this.service = service;
    }

    /**
     * Downloads a profiling result file.
     *
     * <p>Only the bare filename (e.g. {@code bootlens-abc123def456.html}) is
     * accepted. Path separators are rejected to prevent directory traversal.
     *
     * @param section must be {@code download}
     * @param filename the output file name as reported by the stop/status response
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
