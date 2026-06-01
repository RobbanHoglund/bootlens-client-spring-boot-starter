package com.bootlens.client.profiling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Unit tests for {@link AsyncProfilerEndpoint} — covers the query selector routing
 * and the start/stop delegation, all without requiring the async-profiler native
 * library to be loaded.
 *
 * <p>Download operations are tested in {@link AsyncProfilerWebExtensionTest}.
 */
class AsyncProfilerEndpointTest {

    @TempDir
    Path tempDir;

    AsyncProfilerProperties properties;
    AsyncProfilerService service;
    AsyncProfilerEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new AsyncProfilerProperties();
        properties.setOutputDir(tempDir.toString());
        properties.setDefaultDuration(Duration.ofSeconds(10));
        service = new AsyncProfilerService(properties);
        endpoint = new AsyncProfilerEndpoint(service);
    }

    @AfterEach
    void tearDown() {
        service.stop();
        service.shutdown();
    }

    // -------------------------------------------------------------------------
    // query() — selector routing
    // -------------------------------------------------------------------------

    @Test
    void queryReturnsUnknownOperationMessageForBogusSelector() {
        Object result = endpoint.query("bogus-operation", null);

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, ?> map = (Map<String, ?>) result;
        assertThat(map.get("message").toString()).contains("bogus-operation");
        assertThat(map.get("message").toString()).contains("flat, traces, collapsed, samples, version");
    }

    @Test
    void querySamplesReturnsSamplesResultRegardlessOfPlatform() {
        Object result = endpoint.query("samples", null);
        assertThat(result).isInstanceOf(AsyncProfilerService.SamplesResult.class);
    }

    @Test
    void queryVersionReturnsVersionResultRegardlessOfPlatform() {
        Object result = endpoint.query("version", null);
        assertThat(result).isInstanceOf(AsyncProfilerService.VersionResult.class);
    }

    @Test
    void queryFlatReturnsDumpResultRegardlessOfPlatform() {
        Object result = endpoint.query("flat", null);
        assertThat(result).isInstanceOf(AsyncProfilerService.DumpResult.class);
    }

    @Test
    void queryTracesReturnsDumpResultRegardlessOfPlatform() {
        Object result = endpoint.query("traces", null);
        assertThat(result).isInstanceOf(AsyncProfilerService.DumpResult.class);
    }

    @Test
    void queryCollapsedReturnsDumpResultRegardlessOfPlatform() {
        Object result = endpoint.query("collapsed", null);
        assertThat(result).isInstanceOf(AsyncProfilerService.DumpResult.class);
    }

    @Test
    void queryIsCaseInsensitive() {
        Object lower = endpoint.query("flat", null);
        Object upper = endpoint.query("FLAT", null);
        assertThat(lower).isInstanceOf(AsyncProfilerService.DumpResult.class);
        assertThat(upper).isInstanceOf(AsyncProfilerService.DumpResult.class);
    }

    // -------------------------------------------------------------------------
    // start / stop delegation
    // -------------------------------------------------------------------------

    @Test
    void startReturnsUnavailableWhenNativeLibNotLoaded() {
        if (service.status().profilerAvailable()) {
            return; // skip on Linux/macOS where native lib loads
        }
        AsyncProfilerService.StartResult result =
            endpoint.startProfiling(null, null, null, null, null);
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void stopReturnsUnavailableOrNotRunningWhenNativeLibNotLoaded() {
        if (service.status().profilerAvailable()) {
            return; // skip on Linux/macOS
        }
        AsyncProfilerService.StopResult result = endpoint.stopProfiling();
        assertThat(result.status()).isIn("UNAVAILABLE", "NOT_RUNNING");
    }
}
