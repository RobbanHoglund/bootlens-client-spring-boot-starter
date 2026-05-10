package com.bootlens.client.diagnostics;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the BootLens diagnostics actuator endpoint.
 *
 * <p>The defaults intentionally favor safe runtime behavior for a public starter:
 * sensitive output stays blocked, classpath output stays hidden, and heap dump
 * creation/download requires explicit opt-in.</p>
 */
@ConfigurationProperties(prefix = "bootlens.client.diagnostics")
public class BootLensDiagnosticsProperties {

    private boolean enabled = true;
    private boolean endpointEnabled = true;
    private boolean allowSensitive = false;
    private boolean allowExpensive = true;
    private boolean logClassHistogramAtVmExit = false;
    private boolean threadDumpToFile = false;
    private boolean sanitizeSecrets = true;
    private boolean sanitizePrivacy = true;
    private boolean includeClasspath = false;
    private int maxOutputChars = 2_000_000;
    private final HeapDump heapDump = new HeapDump();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEndpointEnabled() {
        return endpointEnabled;
    }

    public void setEndpointEnabled(boolean endpointEnabled) {
        this.endpointEnabled = endpointEnabled;
    }

    public boolean isAllowSensitive() {
        return allowSensitive;
    }

    public void setAllowSensitive(boolean allowSensitive) {
        this.allowSensitive = allowSensitive;
    }

    public boolean isAllowExpensive() {
        return allowExpensive;
    }

    public void setAllowExpensive(boolean allowExpensive) {
        this.allowExpensive = allowExpensive;
    }

    public boolean isLogClassHistogramAtVmExit() {
        return logClassHistogramAtVmExit;
    }

    public void setLogClassHistogramAtVmExit(boolean logClassHistogramAtVmExit) {
        this.logClassHistogramAtVmExit = logClassHistogramAtVmExit;
    }

    public boolean isThreadDumpToFile() {
        return threadDumpToFile;
    }

    public void setThreadDumpToFile(boolean threadDumpToFile) {
        this.threadDumpToFile = threadDumpToFile;
    }

    public boolean isSanitizeSecrets() {
        return sanitizeSecrets;
    }

    public void setSanitizeSecrets(boolean sanitizeSecrets) {
        this.sanitizeSecrets = sanitizeSecrets;
    }

    public boolean isSanitizePrivacy() {
        return sanitizePrivacy;
    }

    public void setSanitizePrivacy(boolean sanitizePrivacy) {
        this.sanitizePrivacy = sanitizePrivacy;
    }

    public boolean isIncludeClasspath() {
        return includeClasspath;
    }

    public void setIncludeClasspath(boolean includeClasspath) {
        this.includeClasspath = includeClasspath;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public HeapDump getHeapDump() {
        return heapDump;
    }

    /**
     * Heap dump-specific controls for the BootLens diagnostics endpoint.
     */
    public static class HeapDump {

        private boolean enabled = false;
        private String directory;
        private boolean includeLiveOnly = true;
        private int maxFiles = 3;
        private Duration maxAge = Duration.ofHours(1);
        private boolean allowDownload = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public boolean isIncludeLiveOnly() {
            return includeLiveOnly;
        }

        public void setIncludeLiveOnly(boolean includeLiveOnly) {
            this.includeLiveOnly = includeLiveOnly;
        }

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = maxFiles;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }

        public boolean isAllowDownload() {
            return allowDownload;
        }

        public void setAllowDownload(boolean allowDownload) {
            this.allowDownload = allowDownload;
        }
    }
}
