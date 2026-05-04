package com.bootlens.client.diagnostics;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum BootLensDiagnosticOperation {
    VM_FLAGS("vm-flags", "VM Flags", "JVM startup flags and runtime arguments.", true, false),
    GC_CLASS_HISTOGRAM("gc-class-histogram", "GC Class Histogram", "Class histogram from the JVM diagnostic command interface.", false, true),
    THREAD_DUMP("thread-dump", "Thread Dump", "Platform thread dump with deadlock summary.", false, true),
    THREAD_DUMP_VT("thread-dump-vt", "Thread Dump With Virtual Threads", "Best-effort merged platform and virtual thread dump.", false, true),
    HEAP_INFO("heap-info", "Heap Info", "Current heap usage and memory manager summary.", false, false),
    HEAP_DUMP("heap-dump", "Heap Dump", "Creates an HPROF heap dump file from the running JVM.", true, true),
    VM_INFORMATION("vm-information", "VM Information", "JVM runtime and operating system information.", false, false),
    COMMAND_LINE("command-line", "Command Line", "Java command line and launch arguments.", true, false),
    METASPACE("metaspace", "Metaspace", "Metaspace and class-space usage.", false, true),
    SYSTEM_PROPERTIES("system-properties", "System Properties", "JVM system properties.", true, false),
    VM_EVENTS("vm-events", "VM Events", "Recent VM event and runtime counters summary.", false, true),
    CLASSES("classes", "Classes", "Class loading summary and loaded class counts.", false, true),
    VIRTUAL_THREADS_INFO("virtual-threads-info", "Virtual Threads Info", "Best-effort virtual thread summary.", false, false),
    SECURITY_REPORT("security-report", "Security Report", "Security-related runtime settings and providers.", true, true),
    ENV("env", "Environment", "Environment variables visible to the JVM process.", true, false);

    private final String id;
    private final String displayName;
    private final String description;
    private final boolean sensitive;
    private final boolean expensive;

    BootLensDiagnosticOperation(String id, String displayName, String description, boolean sensitive, boolean expensive) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.sensitive = sensitive;
        this.expensive = expensive;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public boolean isExpensive() {
        return expensive;
    }

    public static Optional<BootLensDiagnosticOperation> fromInput(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalize(value);
        return Arrays.stream(values())
            .filter(operation -> normalize(operation.name()).equals(normalized) || normalize(operation.id).equals(normalized))
            .findFirst();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
