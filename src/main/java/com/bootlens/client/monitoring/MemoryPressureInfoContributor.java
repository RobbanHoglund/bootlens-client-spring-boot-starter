package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

class MemoryPressureInfoContributor implements InfoContributor {

    private final MemoryPressureMonitor monitor;

    MemoryPressureInfoContributor(MemoryPressureMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void contribute(Info.Builder builder) {
        MemorySnapshot snapshot = monitor.lastSnapshot();
        if (snapshot == null) {
            return;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("heapUsedMb", toMb(snapshot.heapUsed()));
        info.put("heapMaxMb", toMb(snapshot.heapMax()));
        info.put("heapPercent", round(snapshot.heapPercent()));
        if (snapshot.containerUsed() != null) {
            info.put("containerUsedMb", toMb(snapshot.containerUsed()));
        }
        if (snapshot.containerMax() != null) {
            info.put("containerMaxMb", toMb(snapshot.containerMax()));
        }
        if (snapshot.containerPercent() != null) {
            info.put("containerPercent", round(snapshot.containerPercent()));
        }
        info.put("checkedAt", snapshot.checkedAt().toString());
        builder.withDetail("memoryPressure", info);
    }

    private static long toMb(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
