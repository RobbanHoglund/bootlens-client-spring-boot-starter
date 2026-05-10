package com.bootlens.client.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BootLensThreadsUtil {

    private static final Pattern QUOTED_THREAD_HEADER = Pattern.compile("^\"([^\"]+)\"(?:\\s+#(\\d+))?.*$");
    private static final Pattern VIRTUAL_THREAD_HEADER = Pattern.compile("^(VirtualThread\\[[^\\]]+\\].*)$");
    private static final Pattern THREAD_STATE = Pattern.compile("^\\s+java\\.lang\\.Thread\\.State:\\s+(.+)$");

    private BootLensThreadsUtil() {
    }

    public static Map<String, BootLensThreadInfo> createThreadMap(String threadDump) {
        Map<String, BootLensThreadInfo> threads = new LinkedHashMap<>();
        List<String> blocks = splitIntoBlocks(threadDump);
        for (String block : blocks) {
            BootLensThreadInfo info = parseThreadBlock(block);
            if (info != null) {
                threads.merge(info.name(), info, BootLensThreadsUtil::preferRicherThreadInfo);
            }
        }
        return threads;
    }

    public static String getDeadlockInformation(String threadDump) {
        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedThreadIds = threadMxBean.findDeadlockedThreads();
        if (deadlockedThreadIds == null || deadlockedThreadIds.length == 0) {
            return "No deadlocks detected in the current JVM.";
        }

        Map<String, BootLensThreadInfo> threadMap = createThreadMap(threadDump);
        StringBuilder details = new StringBuilder("Deadlocked threads detected in the current JVM:");
        for (long deadlockedThreadId : deadlockedThreadIds) {
            String matchingName = threadMap.values().stream()
                .filter(thread -> thread.threadId() == deadlockedThreadId)
                .map(BootLensThreadInfo::name)
                .findFirst()
                .orElse("thread-" + deadlockedThreadId);
            details.append(System.lineSeparator()).append(" - ").append(matchingName).append(" (#").append(deadlockedThreadId).append(')');
        }
        return details.toString();
    }

    public static String mergeThreadDumpWithVTs(String threadDump, String threadDumpWithVirtualThreads) {
        if (threadDumpWithVirtualThreads == null || threadDumpWithVirtualThreads.isBlank()) {
            return threadDump;
        }
        if (threadDump == null || threadDump.isBlank()) {
            return threadDumpWithVirtualThreads;
        }

        Map<String, BootLensThreadInfo> mergedThreads = new LinkedHashMap<>(createThreadMap(threadDump));
        createThreadMap(threadDumpWithVirtualThreads).forEach(mergedThreads::putIfAbsent);

        StringBuilder mergedDump = new StringBuilder();
        for (BootLensThreadInfo threadInfo : mergedThreads.values()) {
            if (mergedDump.length() > 0) {
                mergedDump.append(System.lineSeparator()).append(System.lineSeparator());
            }
            mergedDump.append(threadInfo.header());
            if (!threadInfo.stackLines().isEmpty()) {
                mergedDump.append(System.lineSeparator())
                    .append(String.join(System.lineSeparator(), threadInfo.stackLines()));
            }
        }
        return mergedDump.toString();
    }

    private static List<String> splitIntoBlocks(String threadDump) {
        if (threadDump == null || threadDump.isBlank()) {
            return List.of();
        }

        List<String> blocks = new ArrayList<>();
        String[] parts = threadDump.split("(?:\\r?\\n){2,}");
        for (String part : parts) {
            if (!part.isBlank()) {
                blocks.add(part.trim());
            }
        }
        return blocks;
    }

    private static BootLensThreadInfo parseThreadBlock(String block) {
        String[] lines = block.split("\\R");
        if (lines.length == 0) {
            return null;
        }

        String header = lines[0];
        Matcher quotedMatcher = QUOTED_THREAD_HEADER.matcher(header);
        Matcher virtualMatcher = VIRTUAL_THREAD_HEADER.matcher(header);

        String name;
        long threadId = -1L;

        if (quotedMatcher.matches()) {
            name = quotedMatcher.group(1);
            if (quotedMatcher.group(2) != null) {
                threadId = parseLong(quotedMatcher.group(2));
            }
        } else if (virtualMatcher.matches()) {
            name = virtualMatcher.group(1);
        } else {
            return null;
        }

        boolean virtualThread = header.contains("VirtualThread") || header.contains(" virtual");
        boolean daemon = header.contains(" daemon ");
        List<String> stackLines = lines.length > 1 ? List.of(lines).subList(1, lines.length) : List.of();
        String state = extractThreadState(stackLines);

        return new BootLensThreadInfo(name, threadId, state, virtualThread, daemon, false, header, List.copyOf(stackLines));
    }

    private static BootLensThreadInfo preferRicherThreadInfo(
        BootLensThreadInfo existing,
        BootLensThreadInfo candidate
    ) {
        boolean existingHasThreadId = existing.threadId() >= 0;
        boolean candidateHasThreadId = candidate.threadId() >= 0;
        if (existingHasThreadId != candidateHasThreadId) {
            return existingHasThreadId ? existing : candidate;
        }

        int existingStackDepth = existing.stackLines() == null ? 0 : existing.stackLines().size();
        int candidateStackDepth = candidate.stackLines() == null ? 0 : candidate.stackLines().size();
        if (existingStackDepth != candidateStackDepth) {
            return existingStackDepth >= candidateStackDepth ? existing : candidate;
        }

        return existing.header().length() >= candidate.header().length() ? existing : candidate;
    }

    private static String extractThreadState(Collection<String> lines) {
        for (String line : lines) {
            Matcher matcher = THREAD_STATE.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1).trim();
            }
        }
        return "UNKNOWN";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }
}
