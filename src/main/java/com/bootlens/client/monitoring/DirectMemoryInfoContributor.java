package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

class DirectMemoryInfoContributor implements InfoContributor {

    private final DirectMemoryMonitor monitor;

    DirectMemoryInfoContributor(DirectMemoryMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void contribute(Info.Builder builder) {
        DirectMemorySnapshot snapshot = monitor.lastSnapshot();
        if (snapshot == null) {
            return;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("available",    snapshot.available());
        info.put("bufferCount",  snapshot.bufferCount());
        info.put("usedMb",       snapshot.usedBytes()     / (1024 * 1024));
        info.put("capacityMb",   snapshot.capacityBytes() / (1024 * 1024));
        if (snapshot.available()) {
            info.put("maxMb",    snapshot.maxBytes() / (1024 * 1024));
            info.put("percent",  round(snapshot.percent()));
        }
        info.put("checkedAt",    snapshot.checkedAt().toString());
        builder.withDetail("directMemory", info);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
