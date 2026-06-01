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
 * Servlet-web extension for the {@link AsyncProfilerEndpoint} that adds the
 * download operation — returning profiling output files (flame graphs, JFR files,
 * collapsed stacks) as HTTP responses.
 *
 * <p>Separated from the main endpoint so that the file-download logic, which uses
 * the web-specific {@link WebEndpointResponse} type, is only activated in a Servlet
 * web context and not in non-web or reactive applications.
 *
 * <p>Accessible at:
 * {@code GET /actuator/bootlensProfiler/download/{filename}}
 */
@EndpointWebExtension(endpoint = AsyncProfilerEndpoint.class)
public class AsyncProfilerWebExtension {

    private final AsyncProfilerService service;

    public AsyncProfilerWebExtension(AsyncProfilerService service) {
        this.service = service;
    }

    /**
     * Downloads a profiling output file by name.
     *
     * <p>The {@code section} path segment must be {@code "download"} (case-insensitive).
     * The filename is validated to prevent path traversal: it must not contain
     * {@code /}, {@code \}, or {@code ..} sequences.
     *
     * @param section  must be {@code "download"}
     * @param filename the exact output filename (e.g. {@code bootlens-abc123def456.html})
     * @return the file as a streaming response, or an appropriate error status
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
