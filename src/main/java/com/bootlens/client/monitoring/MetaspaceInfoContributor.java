package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

class MetaspaceInfoContributor implements InfoContributor {

    private final MetaspaceMonitor monitor;

    MetaspaceInfoContributor(MetaspaceMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void contribute(Info.Builder builder) {
        MetaspaceSnapshot snapshot = monitor.lastSnapshot();
        if (snapshot == null || !snapshot.available()) {
            return;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("usedMb",       snapshot.usedBytes()      / (1024 * 1024));
        info.put("committedMb",  snapshot.committedBytes()  / (1024 * 1024));
        if (snapshot.bounded()) {
            info.put("maxMb",    snapshot.maxBytes() / (1024 * 1024));
            info.put("percent",  round(snapshot.percent()));
        }
        info.put("checkedAt",    snapshot.checkedAt().toString());
        builder.withDetail("metaspace", info);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
