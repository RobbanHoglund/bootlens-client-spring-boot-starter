package com.bootlens.client.monitoring;

import java.time.Instant;

record MetaspaceSnapshot(
    long usedBytes,
    long committedBytes,
    long maxBytes,
    double percent,
    boolean bounded,
    boolean available,
    Instant checkedAt
) {
    static double percentOf(long used, long max) {
        if (max <= 0) return 0.0;
        return 100.0 * used / max;
    }

    static MetaspaceSnapshot unavailable(Instant checkedAt) {
        return new MetaspaceSnapshot(0, 0, -1, 0.0, false, false, checkedAt);
    }
}
