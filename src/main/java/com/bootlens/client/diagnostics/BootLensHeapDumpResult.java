package com.bootlens.client.diagnostics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record BootLensHeapDumpResult(
    String id,
    String fileName,
    long sizeBytes,
    Instant createdAt,
    boolean liveOnly,
    boolean downloadAvailable
) {

    public Map<String, Object> asDetailsMap() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", id);
        details.put("fileName", fileName);
        details.put("sizeBytes", sizeBytes);
        details.put("createdAt", createdAt);
        details.put("liveOnly", liveOnly);
        details.put("downloadAvailable", downloadAvailable);
        return details;
    }
}
