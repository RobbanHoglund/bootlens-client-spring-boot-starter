package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

class ThreadDeadlockInfoContributor implements InfoContributor {

    private final ThreadDeadlockDetector detector;

    ThreadDeadlockInfoContributor(ThreadDeadlockDetector detector) {
        this.detector = detector;
    }

    @Override
    public void contribute(Info.Builder builder) {
        DeadlockSnapshot snapshot = detector.lastSnapshot();
        if (snapshot == null) {
            return;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("deadlocked",   snapshot.deadlocked());
        info.put("threadCount",  snapshot.threadCount());
        if (snapshot.deadlocked()) {
            info.put("threadNames", snapshot.threadNames());
        }
        info.put("checkedAt",    snapshot.checkedAt().toString());
        builder.withDetail("threadDeadlock", info);
    }
}
