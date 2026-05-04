package com.bootlens.client.diagnostics;

import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.annotation.EndpointWebExtension;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeType;

@EndpointWebExtension(endpoint = BootLensDiagnosticsEndpoint.class)
public class BootLensDiagnosticsWebExtension {

    private final HeapDumpManager heapDumpManager;

    public BootLensDiagnosticsWebExtension(HeapDumpManager heapDumpManager) {
        this.heapDumpManager = heapDumpManager;
    }

    @ReadOperation
    public WebEndpointResponse<?> heapDumpDownload(@Selector String section, @Selector String id) {
        if (!"heap-dumps".equalsIgnoreCase(section)) {
            return new WebEndpointResponse<>(Map.of("message", "Unsupported diagnostics download path."), 404);
        }

        return heapDumpManager.findDownload(id)
            .<WebEndpointResponse<?>>map(storedHeapDump -> new WebEndpointResponse<FileSystemResource>(
                new FileSystemResource(storedHeapDump.path()),
                200,
                MimeType.valueOf("application/octet-stream")
            ))
            .orElseGet(() -> new WebEndpointResponse<>(Map.of("message", "Heap dump download is not available."), 404));
    }
}
