package com.bootlens.client.monitoring;

import java.time.Instant;

record GcSnapshot(
    long deltaCollectionCount,
    long deltaPauseMs,
    double pausePercent,
    long totalCollectionCount,
    long totalPauseMs,
    Instant checkedAt
) {

    static double pausePercentOf(long pauseMs, long intervalMs) {
        if (intervalMs <= 0) return 0.0;
        return 100.0 * pauseMs / intervalMs;
    }
}
