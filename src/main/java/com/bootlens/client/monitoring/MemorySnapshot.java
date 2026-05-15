package com.bootlens.client.monitoring;

import java.time.Instant;

record MemorySnapshot(
    long heapUsed,
    long heapMax,
    double heapPercent,
    Long containerUsed,
    Long containerMax,
    Double containerPercent,
    Instant checkedAt
) {

    static double percentOf(long used, long max) {
        if (max <= 0) return 0.0;
        return 100.0 * used / max;
    }

    boolean hasContainerMetrics() {
        return containerUsed != null && containerMax != null;
    }
}
