package com.bootlens.client.monitoring;

import java.time.Instant;

record DirectMemorySnapshot(
    long bufferCount,
    long usedBytes,
    long capacityBytes,
    long maxBytes,
    double percent,
    boolean available,
    Instant checkedAt
) {
    static double percentOf(long used, long max) {
        if (max <= 0) return 0.0;
        return 100.0 * used / max;
    }

    static DirectMemorySnapshot unavailable(Instant checkedAt) {
        return new DirectMemorySnapshot(0, 0, 0, -1, 0.0, false, checkedAt);
    }
}
