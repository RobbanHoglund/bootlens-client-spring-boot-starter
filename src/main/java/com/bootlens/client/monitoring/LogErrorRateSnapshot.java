package com.bootlens.client.monitoring;

import java.time.Instant;

record LogErrorRateSnapshot(
    long errorsInInterval,
    long warnsInInterval,
    long totalErrors,
    long totalWarns,
    Instant checkedAt
) { }
