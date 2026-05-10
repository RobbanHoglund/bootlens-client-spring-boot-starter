package com.bootlens.client.diagnostics;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VmDiagnostics {

    private static final Logger logger = LoggerFactory.getLogger(VmDiagnostics.class);
    private static final String DIAGNOSTIC_COMMAND_MBEAN = "com.sun.management:type=DiagnosticCommand";

    private final MBeanServer mBeanServer;
    private final ObjectName diagnosticCommandObjectName;
    private final SecretSanitizer secretSanitizer;
    private final BootLensDiagnosticsProperties properties;
    private final HeapDumpManager heapDumpManager;

    public VmDiagnostics(SecretSanitizer secretSanitizer, BootLensDiagnosticsProperties properties) {
        this(secretSanitizer, properties, new HeapDumpManager(properties));
    }

    public VmDiagnostics(
        SecretSanitizer secretSanitizer,
        BootLensDiagnosticsProperties properties,
        HeapDumpManager heapDumpManager
    ) {
        this(ManagementFactory.getPlatformMBeanServer(), createDiagnosticCommandObjectName(), secretSanitizer, properties, heapDumpManager);
    }

    VmDiagnostics(
        MBeanServer mBeanServer,
        ObjectName diagnosticCommandObjectName,
        SecretSanitizer secretSanitizer,
        BootLensDiagnosticsProperties properties
    ) {
        this(mBeanServer, diagnosticCommandObjectName, secretSanitizer, properties, new HeapDumpManager(properties));
    }

    VmDiagnostics(
        MBeanServer mBeanServer,
        ObjectName diagnosticCommandObjectName,
        SecretSanitizer secretSanitizer,
        BootLensDiagnosticsProperties properties,
        HeapDumpManager heapDumpManager
    ) {
        this.mBeanServer = mBeanServer;
        this.diagnosticCommandObjectName = diagnosticCommandObjectName;
        this.secretSanitizer = secretSanitizer;
        this.properties = properties;
        this.heapDumpManager = heapDumpManager;
    }

    DiagnosticExecutionResult run(BootLensDiagnosticOperation operation) {
        try {
            if (operation == BootLensDiagnosticOperation.HEAP_DUMP) {
                return createHeapDump();
            }

            DiagnosticExecutionResult result = switch (operation) {
                case VM_FLAGS -> DiagnosticExecutionResult.success(sanitizeOutput(operation, vmFlags()));
                case GC_CLASS_HISTOGRAM -> DiagnosticExecutionResult.success(sanitizeOutput(operation, gcClassHistogram()));
                case THREAD_DUMP -> threadDumpResult();
                case THREAD_DUMP_VT -> threadDumpWithVirtualThreadsResult();
                case HEAP_INFO -> DiagnosticExecutionResult.success(sanitizeOutput(operation, heapInfo()));
                case HEAP_DUMP -> throw new IllegalStateException("Heap dump handled separately");
                case VM_INFORMATION -> DiagnosticExecutionResult.success(sanitizeOutput(operation, vmInformation()));
                case COMMAND_LINE -> DiagnosticExecutionResult.success(sanitizeOutput(operation, commandLine()));
                case METASPACE -> DiagnosticExecutionResult.success(sanitizeOutput(operation, metaspace()));
                case SYSTEM_PROPERTIES -> DiagnosticExecutionResult.success(sanitizeOutput(operation, systemProperties()));
                case VM_EVENTS -> DiagnosticExecutionResult.success(sanitizeOutput(operation, vmEvents()));
                case CLASSES -> DiagnosticExecutionResult.success(sanitizeOutput(operation, classes()));
                case VIRTUAL_THREADS_INFO -> DiagnosticExecutionResult.success(sanitizeOutput(operation, virtualThreadsInfo()));
                case SECURITY_REPORT -> DiagnosticExecutionResult.success(sanitizeOutput(operation, securityReport()));
                case ENV -> DiagnosticExecutionResult.success(sanitizeOutput(operation, environment()));
            };
            return result;
        } catch (Exception exception) {
            logger.warn("BootLens diagnostics operation {} failed: {}", operation.name(), exception.getMessage());
            return DiagnosticExecutionResult.error(sanitizeOutput(operation, "Diagnostic execution failed: " + exception.getMessage()));
        }
    }

    private DiagnosticExecutionResult createHeapDump() {
        HeapDumpManager.HeapDumpTarget target = null;
        try {
            target = heapDumpManager.prepareNewHeapDump();
            if (!invokeHeapDumpCommand(target.path(), properties.getHeapDump().isIncludeLiveOnly())) {
                dumpHeapWithHotSpotMxBean(target.path(), properties.getHeapDump().isIncludeLiveOnly());
            }
            BootLensHeapDumpResult result = heapDumpManager.registerCreatedHeapDump(target);
            return DiagnosticExecutionResult.success(heapDumpManager.renderSuccessMessage(result), result.asDetailsMap());
        } catch (Exception exception) {
            heapDumpManager.deleteIncompleteHeapDump(target);
            logger.warn("BootLens heap dump creation failed: {}", exception.getMessage());
            return DiagnosticExecutionResult.error("Heap dump creation failed: " + exception.getMessage());
        }
    }

    private String vmFlags() {
        return invokeDiagnosticCommand("vmFlags")
            .orElseGet(() -> formatRuntimeArguments("VM Flags", ManagementFactory.getRuntimeMXBean().getInputArguments()));
    }

    private String gcClassHistogram() {
        List<String> arguments = new ArrayList<>();
        if (properties.isLogClassHistogramAtVmExit()) {
            arguments.add("-all=true");
        }
        return invokeDiagnosticCommand("gcClassHistogram", arguments.toArray(String[]::new))
            .orElseGet(() -> classHistogramFallback());
    }

    private DiagnosticExecutionResult threadDumpResult() {
        ThreadDumpCaptureOutcome outcome = captureThreadDumpText();
        String dump = outcome.dumpText() + System.lineSeparator() + System.lineSeparator() + BootLensThreadsUtil.getDeadlockInformation(outcome.dumpText());
        return DiagnosticExecutionResult.success(
            sanitizeOutput(BootLensDiagnosticOperation.THREAD_DUMP, dump),
            Map.of("threadDumpSource", outcome.source()));
    }

    private DiagnosticExecutionResult threadDumpWithVirtualThreadsResult() {
        ThreadDumpCaptureOutcome outcome = captureThreadDumpText();
        String baseDump = outcome.dumpText() + System.lineSeparator() + System.lineSeparator() + BootLensThreadsUtil.getDeadlockInformation(outcome.dumpText());
        String virtualThreadSection = buildVirtualThreadSection();
        String mergedDump = BootLensThreadsUtil.mergeThreadDumpWithVTs(baseDump, virtualThreadSection);
        return DiagnosticExecutionResult.success(
            sanitizeOutput(BootLensDiagnosticOperation.THREAD_DUMP_VT, mergedDump),
            Map.of("threadDumpSource", outcome.source()));
    }

    private String heapInfo() {
        return invokeDiagnosticCommand("heapInfo")
            .orElseGet(() -> {
                MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
                MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
                MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
                StringBuilder output = new StringBuilder("Heap memory");
                appendUsage(output, "Heap", heapUsage);
                appendUsage(output, "Non-heap", nonHeapUsage);
                for (GarbageCollectorMXBean garbageCollectorMXBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    output.append(System.lineSeparator())
                        .append(garbageCollectorMXBean.getName())
                        .append(": collections=")
                        .append(garbageCollectorMXBean.getCollectionCount())
                        .append(", timeMs=")
                        .append(garbageCollectorMXBean.getCollectionTime());
                }
                return output.toString();
            });
    }

    private String vmInformation() {
        return invokeDiagnosticCommand("vmInfo")
            .orElseGet(() -> {
                RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
                OperatingSystemMXBean operatingSystemMxBean = ManagementFactory.getOperatingSystemMXBean();
                CompilationMXBean compilationMXBean = ManagementFactory.getCompilationMXBean();
                StringBuilder output = new StringBuilder();
                output.append("VM name: ").append(runtimeMxBean.getVmName()).append(System.lineSeparator());
                output.append("VM vendor: ").append(runtimeMxBean.getVmVendor()).append(System.lineSeparator());
                output.append("VM version: ").append(runtimeMxBean.getVmVersion()).append(System.lineSeparator());
                output.append("Spec version: ").append(runtimeMxBean.getSpecVersion()).append(System.lineSeparator());
                output.append("Start time: ").append(Instant.ofEpochMilli(runtimeMxBean.getStartTime())).append(System.lineSeparator());
                output.append("Uptime ms: ").append(runtimeMxBean.getUptime()).append(System.lineSeparator());
                output.append("OS: ").append(operatingSystemMxBean.getName()).append(' ')
                    .append(operatingSystemMxBean.getVersion()).append(" (").append(operatingSystemMxBean.getArch()).append(')')
                    .append(System.lineSeparator());
                if (compilationMXBean != null) {
                    output.append("JIT compiler: ").append(compilationMXBean.getName()).append(System.lineSeparator());
                }
                return output.toString();
            });
    }

    private String commandLine() {
        return invokeDiagnosticCommand("vmCommandLine")
            .orElseGet(() -> {
                RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
                StringBuilder output = new StringBuilder();
                output.append("sun.java.command=").append(System.getProperty("sun.java.command", "unknown")).append(System.lineSeparator());
                output.append("java.class.path=").append(System.getProperty("java.class.path", "unknown")).append(System.lineSeparator());
                output.append(formatRuntimeArguments("Input arguments", runtimeMxBean.getInputArguments()));
                return output.toString();
            });
    }

    private String metaspace() {
        StringBuilder output = new StringBuilder();
        List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> {
                String lowerName = pool.getName().toLowerCase();
                return lowerName.contains("metaspace") || lowerName.contains("class");
            })
            .sorted(Comparator.comparing(MemoryPoolMXBean::getName))
            .toList();

        if (memoryPools.isEmpty()) {
            output.append("No metaspace or class memory pools were found.");
        } else {
            output.append("Metaspace and class memory pools");
            for (MemoryPoolMXBean pool : memoryPools) {
                appendUsage(output, pool.getName(), pool.getUsage());
            }
        }

        ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        output.append(System.lineSeparator())
            .append("Loaded classes: ").append(classLoadingMXBean.getLoadedClassCount()).append(System.lineSeparator())
            .append("Total loaded classes: ").append(classLoadingMXBean.getTotalLoadedClassCount()).append(System.lineSeparator())
            .append("Unloaded classes: ").append(classLoadingMXBean.getUnloadedClassCount());
        return output.toString();
    }

    private String systemProperties() {
        Map<String, String> propertiesMap = new TreeMap<>(ManagementFactory.getRuntimeMXBean().getSystemProperties());
        StringBuilder output = new StringBuilder();
        propertiesMap.forEach((key, value) -> output.append(key).append('=').append(value).append(System.lineSeparator()));
        return output.toString();
    }

    private String vmEvents() {
        return invokeDiagnosticCommand("vmEvents")
            .orElseGet(() -> {
                RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
                ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
                StringBuilder output = new StringBuilder();
                output.append("VM event snapshot").append(System.lineSeparator());
                output.append("Start time: ").append(Instant.ofEpochMilli(runtimeMXBean.getStartTime())).append(System.lineSeparator());
                output.append("Uptime ms: ").append(runtimeMXBean.getUptime()).append(System.lineSeparator());
                output.append("Peak thread count: ").append(threadMXBean.getPeakThreadCount()).append(System.lineSeparator());
                for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    output.append(gcBean.getName())
                        .append(": collections=").append(gcBean.getCollectionCount())
                        .append(", timeMs=").append(gcBean.getCollectionTime())
                        .append(System.lineSeparator());
                }
                return output.toString().trim();
            });
    }

    private String classes() {
        ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        StringBuilder output = new StringBuilder();
        output.append("Class loading summary").append(System.lineSeparator());
        output.append("Loaded class count: ").append(classLoadingMXBean.getLoadedClassCount()).append(System.lineSeparator());
        output.append("Total loaded class count: ").append(classLoadingMXBean.getTotalLoadedClassCount()).append(System.lineSeparator());
        output.append("Unloaded class count: ").append(classLoadingMXBean.getUnloadedClassCount());
        invokeDiagnosticCommand("gcClassHistogram").ifPresent(histogram -> output.append(System.lineSeparator()).append(System.lineSeparator()).append(histogram));
        return output.toString();
    }

    private String virtualThreadsInfo() {
        return buildVirtualThreadSection();
    }

    private String securityReport() {
        try {
            Properties systemProperties = System.getProperties();
            SSLContext sslContext = SSLContext.getDefault();
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            SSLServerSocketFactory sslServerSocketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            java.security.Provider[] providers = java.security.Security.getProviders();
            StringBuilder output = new StringBuilder();

            output.append("*** JVM Information ***").append(System.lineSeparator());
            output.append("JVM Version: ").append(System.getProperty("java.version", "unknown")).append(System.lineSeparator());
            output.append("Java Home: ").append(System.getProperty("java.home", "unknown")).append(System.lineSeparator());
            output.append("user.name=").append(systemProperties.getProperty("user.name", "")).append(System.lineSeparator());
            output.append("user.home=").append(systemProperties.getProperty("user.home", "")).append(System.lineSeparator());
            output.append("user.dir=").append(systemProperties.getProperty("user.dir", "")).append(System.lineSeparator());
            output.append("java.io.tmpdir=").append(systemProperties.getProperty("java.io.tmpdir", "")).append(System.lineSeparator());
            output.append("java.class.path=").append(systemProperties.getProperty("java.class.path", "")).append(System.lineSeparator());
            output.append("java.library.path=").append(systemProperties.getProperty("java.library.path", "")).append(System.lineSeparator());
            output.append("sun.java.command=").append(systemProperties.getProperty("sun.java.command", "")).append(System.lineSeparator());
            output.append(System.lineSeparator());

            output.append("*** Security Information ***").append(System.lineSeparator());
            output.append("Supported Cipher Suites:").append(System.lineSeparator());
            for (String cipherSuite : sslSocketFactory.getSupportedCipherSuites()) {
                output.append("  - ").append(cipherSuite).append(System.lineSeparator());
            }
            output.append(System.lineSeparator());

            output.append("Security Providers:").append(System.lineSeparator());
            for (java.security.Provider provider : providers) {
                output.append("  Provider: ").append(provider.getName()).append(System.lineSeparator());
                provider.entrySet().stream()
                    .map(entry -> Map.entry(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
                    .filter(entry -> entry.getKey().toUpperCase().contains("SSL"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> output.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append(System.lineSeparator()));
            }
            output.append(System.lineSeparator());

            output.append("Default SSLSocketFactory:").append(System.lineSeparator());
            output.append(sslSocketFactory).append(System.lineSeparator()).append(System.lineSeparator());

            output.append("Default SSLServerSocketFactory:").append(System.lineSeparator());
            output.append(sslServerSocketFactory).append(System.lineSeparator()).append(System.lineSeparator());

            output.append("Cryptography Providers:").append(System.lineSeparator());
            for (java.security.Provider provider : providers) {
                output.append("Provider: ").append(provider.getName()).append(System.lineSeparator());
                provider.entrySet().stream()
                    .map(entry -> Map.entry(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> output.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator()));
            }

            return output.toString().trim();
        } catch (Exception exception) {
            return "Error generating security report: " + exception.getMessage();
        }
    }

    private String environment() {
        Map<String, String> environment = new TreeMap<>(System.getenv());
        StringBuilder output = new StringBuilder();
        environment.forEach((key, value) -> output.append(key).append('=').append(value).append(System.lineSeparator()));
        return output.toString();
    }

    private String sanitizeOutput(BootLensDiagnosticOperation operation, String output) {
        if (output == null || (!properties.isSanitizeSecrets() && !properties.isSanitizePrivacy())) {
            return output;
        }

        return switch (operation) {
            case ENV, SYSTEM_PROPERTIES, SECURITY_REPORT -> secretSanitizer.sanitizeProperties(output);
            case COMMAND_LINE, VM_FLAGS -> secretSanitizer.sanitizeText(output);
            default -> secretSanitizer.sanitizeText(output);
        };
    }

    private boolean invokeHeapDumpCommand(Path targetPath, boolean liveOnly) {
        List<String> arguments = new ArrayList<>();
        if (!liveOnly) {
            arguments.add("-all");
        }
        arguments.add(targetPath.toAbsolutePath().toString());
        invokeDiagnosticCommand("gcHeapDump", arguments.toArray(String[]::new));
        return Files.exists(targetPath);
    }

    private void dumpHeapWithHotSpotMxBean(Path targetPath, boolean liveOnly) throws IOException {
        try {
            HotSpotDiagnosticMXBean hotSpotDiagnosticMXBean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (hotSpotDiagnosticMXBean == null) {
                throw new IOException("Heap dump is not supported by this JVM.");
            }
            hotSpotDiagnosticMXBean.dumpHeap(targetPath.toAbsolutePath().toString(), liveOnly);
        } catch (RuntimeException exception) {
            throw new IOException("Heap dump command failed: " + exception.getMessage(), exception);
        }
    }

    private ThreadDumpCaptureOutcome captureThreadDumpText() {
        Optional<String> diagnosticDump = invokeDiagnosticCommand("threadPrint", "-l");
        if (diagnosticDump.isPresent() && !diagnosticDump.get().isBlank()) {
            return new ThreadDumpCaptureOutcome(diagnosticDump.get(), "diagnostic-command");
        }

        logger.debug(
            "DiagnosticCommand threadPrint -l was unavailable or returned no data; falling back to ThreadMXBean.dumpAllThreads(true, true).");
        return new ThreadDumpCaptureOutcome(threadDumpFallback(), "mxbean-fallback");
    }

    private String threadDumpFallback() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        StringBuilder output = new StringBuilder();
        for (ThreadInfo threadInfo : threadInfos) {
            if (output.length() > 0) {
                output.append(System.lineSeparator()).append(System.lineSeparator());
            }
            output.append('"').append(threadInfo.getThreadName()).append('"')
                .append(" #").append(threadInfo.getThreadId()).append(System.lineSeparator())
                .append("   java.lang.Thread.State: ").append(threadInfo.getThreadState());
            for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                output.append(System.lineSeparator()).append("\tat ").append(stackTraceElement);
            }
        }
        return output.toString();
    }

    private String buildVirtualThreadSection() {
        Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
        List<Thread> virtualThreads = allStacks.keySet().stream()
            .filter(this::isVirtualThread)
            .sorted(Comparator.comparing(Thread::getName))
            .toList();

        if (virtualThreads.isEmpty()) {
            return "No virtual threads were observed in the current JVM snapshot.";
        }

        StringBuilder output = new StringBuilder("Virtual thread snapshot");
        output.append(System.lineSeparator()).append("Observed virtual threads: ").append(virtualThreads.size());
        for (Thread virtualThread : virtualThreads) {
            output.append(System.lineSeparator()).append(System.lineSeparator());
            output.append("VirtualThread[").append(virtualThread.getName()).append("]")
                .append(" state=").append(virtualThread.getState());
            for (StackTraceElement element : allStacks.getOrDefault(virtualThread, new StackTraceElement[0])) {
                output.append(System.lineSeparator()).append("\tat ").append(element);
            }
        }
        return output.toString();
    }

    private boolean isVirtualThread(Thread thread) {
        try {
            return thread.isVirtual();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String classHistogramFallback() {
        ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        return "Class histogram fallback is unavailable through DiagnosticCommand."
            + System.lineSeparator()
            + "Loaded classes: " + classLoadingMXBean.getLoadedClassCount()
            + System.lineSeparator()
            + "Total loaded classes: " + classLoadingMXBean.getTotalLoadedClassCount()
            + System.lineSeparator()
            + "Unloaded classes: " + classLoadingMXBean.getUnloadedClassCount();
    }

    private void appendUsage(StringBuilder output, String name, MemoryUsage memoryUsage) {
        output.append(System.lineSeparator()).append(name).append(": ");
        if (memoryUsage == null) {
            output.append("usage unavailable");
            return;
        }
        output.append("used=").append(memoryUsage.getUsed())
            .append(", committed=").append(memoryUsage.getCommitted())
            .append(", max=").append(memoryUsage.getMax());
    }

    private String formatRuntimeArguments(String title, List<String> arguments) {
        if (arguments.isEmpty()) {
            return title + ": no arguments reported";
        }
        return title + System.lineSeparator() + arguments.stream().collect(Collectors.joining(System.lineSeparator()));
    }

    private Optional<String> invokeDiagnosticCommand(String operationName, String... arguments) {
        try {
            if (!mBeanServer.isRegistered(diagnosticCommandObjectName)) {
                return Optional.empty();
            }
            if (!isOperationAvailable(operationName)) {
                return Optional.empty();
            }

            Object result = mBeanServer.invoke(
                diagnosticCommandObjectName,
                operationName,
                new Object[] { arguments },
                new String[] { String[].class.getName() }
            );
            return Optional.ofNullable(result).map(Object::toString);
        } catch (InstanceNotFoundException exception) {
            return Optional.empty();
        } catch (JMException exception) {
            logger.debug("Diagnostic command {} failed: {}", operationName, exception.getMessage());
            return Optional.empty();
        }
    }

    private boolean isOperationAvailable(String operationName) {
        try {
            MBeanInfo mBeanInfo = mBeanServer.getMBeanInfo(diagnosticCommandObjectName);
            for (MBeanOperationInfo operationInfo : mBeanInfo.getOperations()) {
                if (operationInfo.getName().equals(operationName)) {
                    return true;
                }
            }
        } catch (JMException exception) {
            logger.debug("Unable to inspect DiagnosticCommand MBean operations: {}", exception.getMessage());
        }
        return false;
    }

    private static ObjectName createDiagnosticCommandObjectName() {
        try {
            return new ObjectName(DIAGNOSTIC_COMMAND_MBEAN);
        } catch (JMException exception) {
            throw new IllegalStateException("Failed to create DiagnosticCommand object name", exception);
        }
    }

    private boolean isSecurityManagerPresent() {
        try {
            Object securityManager = System.class.getMethod("getSecurityManager").invoke(null);
            return securityManager != null;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private record ThreadDumpCaptureOutcome(String dumpText, String source) {
    }
}
