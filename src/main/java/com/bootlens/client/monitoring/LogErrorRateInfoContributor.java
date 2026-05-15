package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

class LogErrorRateInfoContributor implements InfoContributor {

    private final LogErrorRateMonitor monitor;

    LogErrorRateInfoContributor(LogErrorRateMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void contribute(Info.Builder builder) {
        LogErrorRateSnapshot snapshot = monitor.lastSnapshot();
        if (snapshot == null) {
            return;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("errorsInInterval", snapshot.errorsInInterval());
        info.put("warnsInInterval",  snapshot.warnsInInterval());
        info.put("totalErrors",      snapshot.totalErrors());
        info.put("totalWarns",       snapshot.totalWarns());
        info.put("checkedAt",        snapshot.checkedAt().toString());
        builder.withDetail("logErrors", info);
    }
}
