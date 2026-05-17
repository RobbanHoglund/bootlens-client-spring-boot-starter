package com.bootlens.client.diagnostics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;

public class BootLensHealthDiagnosticsService {

    private static final long SLOW_THRESHOLD_MS = 500L;
    private static final long VERY_SLOW_THRESHOLD_MS = 2_000L;

    private final BootLensDiagnosticsProperties properties;
    private final ObjectProvider<HealthContributorRegistry> healthContributorRegistry;
    private final ObjectProvider<HealthEndpointGroups> healthEndpointGroups;

    public BootLensHealthDiagnosticsService(
        BootLensDiagnosticsProperties properties,
        ObjectProvider<HealthContributorRegistry> healthContributorRegistry,
        ObjectProvider<HealthEndpointGroups> healthEndpointGroups
    ) {
        this.properties = properties;
        this.healthContributorRegistry = healthContributorRegistry;
        this.healthEndpointGroups = healthEndpointGroups;
    }

    public HealthLatencyDiagnostics capture() {
        if (!properties.getHealthDiagnostics().isEnabled()) {
            return new HealthLatencyDiagnostics(
                Instant.now(),
                "UNAVAILABLE",
                0L,
                List.of(),
                List.of(),
                "Health diagnostics disabled by configuration.");
        }
        if (healthContributorRegistry.getIfAvailable() == null) {
            return new HealthLatencyDiagnostics(
                Instant.now(),
                "UNAVAILABLE",
                0L,
                List.of(),
                List.of(),
                "Health contributor registry is not available in this application context.");
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bootlens-health-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<HealthLatencyDiagnostics> future = executor.submit(this::captureBlocking);
            return future.get(properties.getHealthDiagnostics().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException exception) {
            return new HealthLatencyDiagnostics(
                Instant.now(),
                "TIMEOUT",
                properties.getHealthDiagnostics().getTimeout().toMillis(),
                List.of(),
                List.of(),
                "Health diagnostics timed out before contributor timing completed.");
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new HealthLatencyDiagnostics(
                Instant.now(),
                "FAILED",
                null,
                List.of(),
                List.of(),
                "Health diagnostics were interrupted.");
        }
        catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return new HealthLatencyDiagnostics(
                Instant.now(),
                "FAILED",
                null,
                List.of(),
                List.of(),
                firstNonBlank(cause.getMessage(), "Health diagnostics failed unexpectedly."));
        }
        finally {
            executor.shutdownNow();
        }
    }

    private HealthLatencyDiagnostics captureBlocking() {
        Instant capturedAt = Instant.now();
        long startedNanos = System.nanoTime();
        List<HealthContributorTiming> contributors = new ArrayList<>();
        Map<String, GroupAccumulator> groupAccumulators = new LinkedHashMap<>();
        HealthEndpointGroups groups = healthEndpointGroups.getIfAvailable();
        int maxContributorCount = Math.max(1, properties.getHealthDiagnostics().getMaxContributorCount());
        HealthContributorRegistry registry = healthContributorRegistry.getObject();

        for (HealthContributors.Entry entry : registry) {
            if (contributors.size() >= maxContributorCount) {
                break;
            }
            captureContributor(entry.name(), entry.name(), entry.contributor(), contributors, groupAccumulators, groups, maxContributorCount);
        }

        contributors.sort(Comparator.comparingLong((HealthContributorTiming timing) -> timing.durationMs() == null ? -1L : timing.durationMs()).reversed()
            .thenComparing(HealthContributorTiming::path));
        List<HealthLatencyGroup> groupViews = groupAccumulators.values().stream()
            .map(GroupAccumulator::toView)
            .sorted(Comparator.comparingLong((HealthLatencyGroup group) -> group.totalDurationMs() == null ? -1L : group.totalDurationMs()).reversed())
            .toList();
        long totalDurationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        String status = aggregateStatusCode(contributors, groups);
        return new HealthLatencyDiagnostics(
            capturedAt,
            status,
            totalDurationMs,
            groupViews,
            List.copyOf(contributors),
            null);
    }

    private void captureContributor(
        String rootName,
        String path,
        HealthContributor contributor,
        List<HealthContributorTiming> contributors,
        Map<String, GroupAccumulator> groupAccumulators,
        HealthEndpointGroups groups,
        int maxContributorCount
    ) {
        if (contributors.size() >= maxContributorCount || contributor == null) {
            return;
        }
        if (contributor instanceof CompositeHealthContributor composite) {
            for (HealthContributors.Entry entry : composite) {
                if (contributors.size() >= maxContributorCount) {
                    break;
                }
                captureContributor(rootName, path + "/" + entry.name(), entry.contributor(), contributors, groupAccumulators, groups, maxContributorCount);
            }
            return;
        }
        if (!(contributor instanceof HealthIndicator indicator)) {
            contributors.add(new HealthContributorTiming(
                leafName(path),
                path,
                resolveGroupName(rootName, groups),
                "UNKNOWN",
                null,
                false,
                false,
                true,
                "Unsupported health contributor type: " + contributor.getClass().getSimpleName(),
                null,
                inferDependencyRelated(path, contributor)));
            return;
        }

        long startedNanos = System.nanoTime();
        String statusCode;
        String failureReason = null;
        String detailsSummary = null;
        boolean error = false;
        try {
            Health health = indicator.health(true);
            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            statusCode = health == null || health.getStatus() == null ? "UNKNOWN" : health.getStatus().getCode();
            detailsSummary = summarizeDetails(health == null ? Map.of() : health.getDetails());
            boolean dependencyRelated = inferDependencyRelated(path, contributor);
            String groupName = resolveGroupName(rootName, groups);
            HealthContributorTiming timing = new HealthContributorTiming(
                leafName(path),
                path,
                groupName,
                statusCode,
                durationMs,
                durationMs >= SLOW_THRESHOLD_MS,
                durationMs >= VERY_SLOW_THRESHOLD_MS,
                false,
                null,
                detailsSummary,
                dependencyRelated);
            contributors.add(timing);
            recordGroup(groupAccumulators, groupName, statusCode, durationMs);
            return;
        }
        catch (RuntimeException exception) {
            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            statusCode = "DOWN";
            failureReason = firstNonBlank(exception.getMessage(), exception.getClass().getSimpleName());
            error = true;
            String groupName = resolveGroupName(rootName, groups);
            contributors.add(new HealthContributorTiming(
                leafName(path),
                path,
                groupName,
                statusCode,
                durationMs,
                durationMs >= SLOW_THRESHOLD_MS,
                durationMs >= VERY_SLOW_THRESHOLD_MS,
                error,
                failureReason,
                null,
                inferDependencyRelated(path, contributor)));
            recordGroup(groupAccumulators, groupName, statusCode, durationMs);
        }
    }

    private void recordGroup(Map<String, GroupAccumulator> groupAccumulators, String groupName, String statusCode, long durationMs) {
        groupAccumulators.computeIfAbsent(groupName, GroupAccumulator::new).record(statusCode, durationMs);
    }

    private String aggregateStatusCode(List<HealthContributorTiming> contributors, HealthEndpointGroups groups) {
        Set<Status> statuses = new LinkedHashSet<>();
        for (HealthContributorTiming contributor : contributors) {
            statuses.add(new Status(firstNonBlank(contributor.status(), "UNKNOWN")));
        }
        if (statuses.isEmpty()) {
            return "UNKNOWN";
        }
        if (groups != null && groups.getPrimary() != null) {
            return groups.getPrimary().getStatusAggregator().getAggregateStatus(statuses).getCode();
        }
        return statuses.stream()
            .map(Status::getCode)
            .map(code -> code == null ? "UNKNOWN" : code)
            .sorted(this::statusPriority)
            .findFirst()
            .orElse("UNKNOWN");
    }

    private int statusPriority(String left, String right) {
        return Integer.compare(statusRank(left), statusRank(right));
    }

    private int statusRank(String status) {
        return switch (status == null ? "UNKNOWN" : status.toUpperCase(Locale.ROOT)) {
            case "DOWN" -> 0;
            case "OUT_OF_SERVICE" -> 1;
            case "UP" -> 2;
            default -> 3;
        };
    }

    private String summarizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        int maxEntries = Math.max(1, properties.getHealthDiagnostics().getMaxDetailsEntries());
        List<String> keys = details.keySet().stream().limit(maxEntries).toList();
        String prefix = keys.size() == 1 ? "1 detail key" : keys.size() + " detail keys";
        if (details.size() > keys.size()) {
            return prefix + " | " + String.join(", ", keys) + ", +" + (details.size() - keys.size()) + " more";
        }
        return prefix + " | " + String.join(", ", keys);
    }

    private String resolveGroupName(String rootName, HealthEndpointGroups groups) {
        if (groups == null) {
            return "primary";
        }
        for (String name : groups.getNames()) {
            HealthEndpointGroup group = groups.get(name);
            if (group != null && group.isMember(rootName)) {
                return name;
            }
        }
        HealthEndpointGroup primary = groups.getPrimary();
        if (primary != null && primary.isMember(rootName)) {
            return "primary";
        }
        return "primary";
    }

    private boolean inferDependencyRelated(String path, HealthContributor contributor) {
        String fingerprint = (path + "|" + contributor.getClass().getName()).toLowerCase(Locale.ROOT);
        return fingerprint.contains("db")
            || fingerprint.contains("jdbc")
            || fingerprint.contains("datasource")
            || fingerprint.contains("redis")
            || fingerprint.contains("mongo")
            || fingerprint.contains("cassandra")
            || fingerprint.contains("elasticsearch")
            || fingerprint.contains("rabbit")
            || fingerprint.contains("kafka")
            || fingerprint.contains("mail")
            || fingerprint.contains("solr")
            || fingerprint.contains("ldap")
            || fingerprint.contains("diskspace")
            || fingerprint.contains("ping");
    }

    private String leafName(String path) {
        int delimiter = path.lastIndexOf('/');
        return delimiter >= 0 ? path.substring(delimiter + 1) : path;
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    public record HealthLatencyDiagnostics(
        Instant capturedAt,
        String status,
        Long totalDurationMs,
        List<HealthLatencyGroup> groups,
        List<HealthContributorTiming> contributors,
        String error
    ) {
    }

    public record HealthLatencyGroup(
        String name,
        String status,
        Long totalDurationMs,
        Integer contributorCount
    ) {
    }

    public record HealthContributorTiming(
        String name,
        String path,
        String group,
        String status,
        Long durationMs,
        boolean slow,
        boolean verySlow,
        boolean error,
        String failureReason,
        String detailsSummary,
        boolean dependencyRelated
    ) {
    }

    private static final class GroupAccumulator {
        private final String name;
        private final List<String> statuses = new ArrayList<>();
        private long totalDurationMs;

        private GroupAccumulator(String name) {
            this.name = name;
        }

        private void record(String status, long durationMs) {
            statuses.add(status);
            totalDurationMs += Math.max(0L, durationMs);
        }

        private HealthLatencyGroup toView() {
            return new HealthLatencyGroup(
                name,
                statuses.stream().map(value -> value == null ? "UNKNOWN" : value).reduce("UP", (left, right) -> rank(left) <= rank(right) ? left : right),
                totalDurationMs,
                statuses.size());
        }

        private int rank(String status) {
            return switch (status == null ? "UNKNOWN" : status.toUpperCase(Locale.ROOT)) {
                case "DOWN" -> 0;
                case "OUT_OF_SERVICE" -> 1;
                case "UP" -> 2;
                default -> 3;
            };
        }
    }
}
