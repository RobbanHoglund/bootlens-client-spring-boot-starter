package com.bootlens.client.profiling;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the embedded async-profiler integration.
 *
 * <p>All settings are under the {@code bootlens.client.profiler} prefix.
 */
@ConfigurationProperties("bootlens.client.profiler")
public class AsyncProfilerProperties {

    /** Whether the embedded async-profiler is enabled. */
    private boolean enabled = true;

    /**
     * Directory where profiling output files are written.
     * Defaults to {@code ${java.io.tmpdir}/bootlens-profiles}.
     */
    private String outputDir;

    /**
     * Profiling event to use when none is specified.
     * Accepts async-profiler event names ({@code cpu}, {@code alloc}, {@code wall},
     * {@code lock}, {@code ctimer}, {@code nativemem}, {@code nativememleak})
     * or any case/hyphen variant recognised by {@link ProfilerEvent#fromString}.
     */
    private String defaultEvent = "cpu";

    /** Profiling duration to use when none is specified. */
    private Duration defaultDuration = Duration.ofSeconds(30);

    /** Output format to use when none is specified. */
    private OutputFormat defaultFormat = OutputFormat.FLAMEGRAPH;

    /**
     * Maximum allowed profiling duration. Requests for longer durations are
     * capped at this value to prevent runaway profiling sessions.
     */
    private Duration maxDuration = Duration.ofSeconds(300);

    /**
     * Maximum stack depth captured per sample. Defaults to {@code 0} which lets
     * async-profiler use its own default (2048 frames). Reduce this (e.g. to 64)
     * to lower overhead when deep stacks are not interesting.
     * Corresponds to the {@code jstackdepth} async-profiler option.
     */
    private int jstackdepth = 0;

    /**
     * When {@code true}, profile threads separately: each stack trace ends with a
     * frame identifying the thread. Especially useful with wall-clock profiling.
     * Corresponds to the {@code threads} async-profiler option.
     */
    private boolean threads = false;

    /**
     * Maximum number of methods shown by the {@code /flat} in-memory dump.
     */
    private int dumpFlatMaxMethods = 50;

    /**
     * Maximum number of call traces shown by the {@code /traces} in-memory dump.
     */
    private int dumpTracesMaxTraces = 10;

    /**
     * Supported profiling output formats.
     */
    public enum OutputFormat {
        /** Interactive HTML flame graph — best for visual exploration. */
        FLAMEGRAPH(".html"),
        /** JDK Flight Recorder format — compatible with JDK Mission Control and IntelliJ. */
        JFR(".jfr"),
        /** Interactive HTML call tree. */
        TREE(".html"),
        /** FlameGraph-compatible collapsed stacks (plain text). */
        COLLAPSED(".txt");

        private final String fileExtension;

        OutputFormat(String fileExtension) {
            this.fileExtension = fileExtension;
        }

        public String getFileExtension() {
            return fileExtension;
        }

        /** The lowercase name used in the async-profiler {@code execute} command. */
        public String getCommandValue() {
            return name().toLowerCase();
        }

        public static OutputFormat fromString(String value) {
            if (value == null) {
                return null;
            }
            for (OutputFormat format : values()) {
                if (format.name().equalsIgnoreCase(value)) {
                    return format;
                }
            }
            throw new IllegalArgumentException(
                "Unknown profiling format '" + value + "'. Valid values: flamegraph, jfr, tree, collapsed");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOutputDir() {
        if (outputDir == null || outputDir.isBlank()) {
            return System.getProperty("java.io.tmpdir") + "/bootlens-profiles";
        }
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public String getDefaultEvent() {
        return defaultEvent;
    }

    public void setDefaultEvent(String defaultEvent) {
        this.defaultEvent = defaultEvent;
    }

    public Duration getDefaultDuration() {
        return defaultDuration;
    }

    public void setDefaultDuration(Duration defaultDuration) {
        this.defaultDuration = defaultDuration;
    }

    public OutputFormat getDefaultFormat() {
        return defaultFormat;
    }

    public void setDefaultFormat(OutputFormat defaultFormat) {
        this.defaultFormat = defaultFormat;
    }

    public Duration getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(Duration maxDuration) {
        this.maxDuration = maxDuration;
    }

    public int getJstackdepth() {
        return jstackdepth;
    }

    public void setJstackdepth(int jstackdepth) {
        this.jstackdepth = jstackdepth;
    }

    public boolean isThreads() {
        return threads;
    }

    public void setThreads(boolean threads) {
        this.threads = threads;
    }

    public int getDumpFlatMaxMethods() {
        return dumpFlatMaxMethods;
    }

    public void setDumpFlatMaxMethods(int dumpFlatMaxMethods) {
        this.dumpFlatMaxMethods = dumpFlatMaxMethods;
    }

    public int getDumpTracesMaxTraces() {
        return dumpTracesMaxTraces;
    }

    public void setDumpTracesMaxTraces(int dumpTracesMaxTraces) {
        this.dumpTracesMaxTraces = dumpTracesMaxTraces;
    }

}
