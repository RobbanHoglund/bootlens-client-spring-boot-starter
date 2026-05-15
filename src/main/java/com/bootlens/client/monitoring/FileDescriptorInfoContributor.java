package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

class FileDescriptorInfoContributor implements InfoContributor {

    private final FileDescriptorMonitor monitor;

    FileDescriptorInfoContributor(FileDescriptorMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void contribute(Info.Builder builder) {
        FileDescriptorSnapshot snapshot = monitor.lastSnapshot();
        if (snapshot == null) {
            return;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("available", snapshot.available());
        if (snapshot.available()) {
            info.put("open",    snapshot.open());
            info.put("max",     snapshot.max());
            info.put("percent", round(snapshot.percent()));
        }
        info.put("checkedAt", snapshot.checkedAt().toString());
        builder.withDetail("fileDescriptors", info);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
