package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

class GcPressureInfoContributor implements InfoContributor {

    private final GcPressureMonitor monitor;

    GcPressureInfoContributor(GcPressureMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void contribute(Info.Builder builder) {
        GcSnapshot snapshot = monitor.lastSnapshot();
        if (snapshot == null) {
            return;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("intervalCollections",  snapshot.deltaCollectionCount());
        info.put("intervalPauseMs",      snapshot.deltaPauseMs());
        info.put("intervalPausePercent", round(snapshot.pausePercent()));
        info.put("totalCollections",     snapshot.totalCollectionCount());
        info.put("totalPauseMs",         snapshot.totalPauseMs());
        info.put("checkedAt",            snapshot.checkedAt().toString());
        builder.withDetail("gcPressure", info);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
