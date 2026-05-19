package com.bootlens.client.profiling;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Typed representation of async-profiler events.
 *
 * <p>{@link #fromString(String)} accepts the async-profiler external name
 * ({@code cpu}, {@code nativemem}, …), the Java enum constant name
 * ({@code CPU}, {@code NATIVE_MEM}, …) or any mixture of case, hyphens, and
 * underscores — so callers need not worry about exact spelling.
 */
public enum ProfilerEvent {

    CPU("cpu"),
    WALL("wall"),
    CTIMER("ctimer"),
    ALLOC("alloc"),
    LOCK("lock"),
    NATIVE_MEM("nativemem"),

    /**
     * Native memory <em>leak</em> profiling — same event as {@link #NATIVE_MEM}
     * but adds the {@code leak} flag so only un-freed allocations are retained.
     */
    NATIVE_MEM_LEAK("nativememleak");

    private final String externalName;

    ProfilerEvent(String externalName) {
        this.externalName = externalName;
    }

    /**
     * The value used in the async-profiler {@code execute} command string,
     * e.g. {@code "cpu"}, {@code "nativemem"}.
     */
    public String externalName() {
        return externalName;
    }

    /**
     * Resolves a profiler event from a free-form string. Accepts external names
     * ({@code "cpu"}), enum constant names ({@code "CPU"}), and relaxed variants
     * with underscores/hyphens ({@code "native-mem"}, {@code "NATIVE_MEM_LEAK"}).
     *
     * @param value the string to parse; {@code null} or blank returns empty
     * @return the matching event, or empty if no match
     */
    public static Optional<ProfilerEvent> fromString(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(value);
        String compact    = compact(value);
        return Arrays.stream(values())
            .filter(event ->
                event.externalName.equals(normalized)
                    || compact(event.name()).equals(compact)
                    || compact(event.externalName).equals(compact))
            .findFirst();
    }

    private static String normalize(String value) {
        return value.trim().replace('_', '-').replace(' ', '-').toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        return value.trim()
            .replace("_", "").replace("-", "").replace(" ", "")
            .toUpperCase(Locale.ROOT);
    }
}
