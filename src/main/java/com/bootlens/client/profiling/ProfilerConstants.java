package com.bootlens.client.profiling;

/**
 * Sensible per-event default parameters for the embedded async-profiler.
 *
 * <p>These mirror values used in production and can be overridden via the
 * {@code interval} parameter on the {@link AsyncProfilerService#start} method.
 */
final class ProfilerConstants {

    private ProfilerConstants() {
        throw new AssertionError("No instances");
    }

    // -------------------------------------------------------------------------
    // Per-event default sampling intervals / thresholds
    // -------------------------------------------------------------------------

    /** CPU profiling: 2 ms between samples — low overhead, good resolution. */
    static final String CPU_INTERVAL = "2ms";

    /** Wall-clock profiling: 100 ms between samples. */
    static final String WALL_INTERVAL = "100ms";

    /** ctimer (context timer) profiling: 100 ms between samples. */
    static final String CTIMER_INTERVAL = "100ms";

    /** Allocation profiling: sample after every 1 KB of heap pressure on average. */
    static final String ALLOC_THRESHOLD = "1k";

    /** Lock profiling: record contention events where wait time exceeds 5 ms. */
    static final String LOCK_THRESHOLD = "5ms";

    /** Native memory profiling: sample after every 1 KB of allocation on average. */
    static final String NATIVEMEM_THRESHOLD = "1k";

    // -------------------------------------------------------------------------
    // Safety limits
    // -------------------------------------------------------------------------

    /** Maximum output file size the download endpoint will serve (100 MB). */
    static final long MAX_OUTPUT_BYTES = 100L * 1024L * 1024L;
}
