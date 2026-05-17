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

    /** Profiling event to use when none is specified (cpu, alloc, wall, lock). */
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
}
