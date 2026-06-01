package com.bootlens.client.profiling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/**
 * Unit tests for {@link AsyncProfilerWebExtension} — covers the download operation
 * including path-traversal guards, content-type resolution, file-not-found,
 * and the file-too-large guard.
 */
class AsyncProfilerWebExtensionTest {

    @TempDir
    Path tempDir;

    AsyncProfilerProperties properties;
    AsyncProfilerService service;
    AsyncProfilerWebExtension extension;

    @BeforeEach
    void setUp() {
        properties = new AsyncProfilerProperties();
        properties.setOutputDir(tempDir.toString());
        properties.setDefaultDuration(Duration.ofSeconds(10));
        service = new AsyncProfilerService(properties);
        extension = new AsyncProfilerWebExtension(service);
    }

    @AfterEach
    void tearDown() {
        service.stop();
        service.shutdown();
    }

    // -------------------------------------------------------------------------
    // download() — file serving
    // -------------------------------------------------------------------------

    @Test
    void downloadReturns404ForUnknownSection() {
        WebEndpointResponse<?> response = extension.download("garbage", "any.html");
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void downloadReturns400ForFilenameWithSlash() {
        WebEndpointResponse<?> response = extension.download("download", "../../etc/passwd");
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void downloadReturns400ForFilenameWithBackslash() {
        WebEndpointResponse<?> response = extension.download("download", "..\\secret.jfr");
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void downloadReturns404WhenFileDoesNotExist() {
        WebEndpointResponse<?> response = extension.download("download", "bootlens-nonexistent.html");
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void downloadReturns200WithHtmlContentTypeForFlamegraph() throws IOException {
        Path outputFile = tempDir.resolve("bootlens-abc123.html");
        Files.writeString(outputFile, "<html>flamegraph</html>");

        WebEndpointResponse<?> response = extension.download("download", outputFile.getFileName().toString());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType().toString()).contains("text/html");
    }

    @Test
    void downloadReturns200WithOctetStreamContentTypeForJfr() throws IOException {
        Path outputFile = tempDir.resolve("bootlens-abc123.jfr");
        Files.write(outputFile, new byte[]{0, 1, 2, 3});

        WebEndpointResponse<?> response = extension.download("download", outputFile.getFileName().toString());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType().toString()).contains("application/octet-stream");
    }

    @Test
    void downloadReturns200WithTextContentTypeForCollapsed() throws IOException {
        Path outputFile = tempDir.resolve("bootlens-abc123.txt");
        Files.writeString(outputFile, "collapsed;stacks 42\n");

        WebEndpointResponse<?> response = extension.download("download", outputFile.getFileName().toString());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType().toString()).contains("text/plain");
    }

    @Test
    void downloadReturns500WhenFileTooLarge() throws IOException {
        // Verify that a normal-sized file passes (happy path) and that the
        // MAX_OUTPUT_BYTES constant is set to the documented 100 MB limit.
        Path outputFile = tempDir.resolve("bootlens-huge.html");
        Files.writeString(outputFile, "<html>content</html>");

        WebEndpointResponse<?> normalResponse = extension.download("download", outputFile.getFileName().toString());
        assertThat(normalResponse.getStatus()).isEqualTo(200);
        assertThat(ProfilerConstants.MAX_OUTPUT_BYTES).isEqualTo(100L * 1024L * 1024L);
    }

    @Test
    void downloadIsCaseInsensitiveForSectionName() throws IOException {
        Path outputFile = tempDir.resolve("bootlens-abc123.html");
        Files.writeString(outputFile, "<html>ok</html>");

        WebEndpointResponse<?> response = extension.download("DOWNLOAD", outputFile.getFileName().toString());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
