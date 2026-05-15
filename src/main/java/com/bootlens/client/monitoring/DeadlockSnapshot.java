package com.bootlens.client.monitoring;

import java.time.Instant;
import java.util.List;

record DeadlockSnapshot(
    boolean deadlocked,
    int threadCount,
    List<String> threadNames,
    Instant checkedAt
) {
    static DeadlockSnapshot clean(Instant checkedAt) {
        return new DeadlockSnapshot(false, 0, List.of(), checkedAt);
    }
}
