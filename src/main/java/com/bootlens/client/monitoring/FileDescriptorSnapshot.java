package com.bootlens.client.monitoring;

import java.time.Instant;

record FileDescriptorSnapshot(
    long open,
    long max,
    double percent,
    boolean available,
    Instant checkedAt
) {

    static FileDescriptorSnapshot unavailable(Instant checkedAt) {
        return new FileDescriptorSnapshot(0, 0, 0.0, false, checkedAt);
    }

    static double percentOf(long open, long max) {
        if (max <= 0) return 0.0;
        return 100.0 * open / max;
    }
}
