package com.bootlens.client.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "thread.deadlock")
public class ThreadDeadlockProperties {

    private boolean enabled = true;
    private Duration checkInterval = Duration.ofSeconds(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getCheckInterval() { return checkInterval; }
    public void setCheckInterval(Duration checkInterval) { this.checkInterval = checkInterval; }
}
