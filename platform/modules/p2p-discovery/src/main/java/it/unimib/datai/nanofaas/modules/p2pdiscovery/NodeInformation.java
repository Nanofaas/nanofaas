package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;
import java.time.Instant;
import java.util.List;

/** Versioned application snapshot; it contains only observations of its originating node. */
public record NodeInformation(int schemaVersion, String nodeId, Instant sampledAt,
                              Category<List<FunctionInfo>> functions, Category<ImageInventory> images,
                              Category<ResourceInfo> resources) {
    public enum Status { AVAILABLE, PARTIAL, DISABLED, UNAVAILABLE }

    public record Category<T>(Status status, Instant collectedAt, String source, String scope,
                              String reasonCode, T data, long ageMillis) {
        public Category {
            if (status == null || ageMillis < 0) throw new IllegalArgumentException("invalid category metadata");
            boolean available = status == Status.AVAILABLE || status == Status.PARTIAL;
            if (available && (data == null || collectedAt == null || source == null || scope == null)) {
                throw new IllegalArgumentException("observations require data and source metadata");
            }
            if (!available && data != null) throw new IllegalArgumentException("unavailable category cannot carry data");
        }

        public static <T> Category<T> disabled() {
            return new Category<>(Status.DISABLED, null, null, null, null, null, 0);
        }

        public static <T> Category<T> unavailable(String reason) {
            return new Category<>(Status.UNAVAILABLE, null, null, null, reason, null, 0);
        }

        public Category<T> aged(long millis) {
            return millis >= 15000 ? unavailable("STALE")
                    : new Category<>(status, collectedAt, source, scope, reasonCode, data, millis);
        }
    }

    public record FunctionInfo(String name, String executionMode, String image, String backend) {
        public FunctionInfo {
            if (name == null || name.isBlank() || executionMode == null
                    || !List.of("LOCAL", "EXTERNAL", "DEPLOYMENT").contains(executionMode)) {
                throw new IllegalArgumentException("invalid function summary");
            }
        }
    }

    /** Environment bytes, process CPU ratio, and JVM heap bytes are distinct measurement scopes. */
    public record ResourceInfo(Double environmentCpuRatio, Long environmentMemoryUsedBytes,
                               Long environmentMemoryTotalBytes, Double processCpuRatio,
                               Long jvmHeapUsedBytes, Long jvmHeapMaxBytes, List<FunctionLoad> functions) {
        public ResourceInfo {
            ratio(environmentCpuRatio);
            ratio(processCpuRatio);
            bytes(environmentMemoryUsedBytes);
            bytes(environmentMemoryTotalBytes);
            bytes(jvmHeapUsedBytes);
            bytes(jvmHeapMaxBytes);
            if (environmentMemoryUsedBytes != null && environmentMemoryTotalBytes != null
                    && environmentMemoryUsedBytes > environmentMemoryTotalBytes) {
                throw new IllegalArgumentException("used memory exceeds total");
            }
            functions = List.copyOf(functions);
        }
        private static void ratio(Double value) {
            if (value != null && (!Double.isFinite(value) || value < 0 || value > 1)) {
                throw new IllegalArgumentException("CPU utilization must be a finite ratio in [0,1]");
            }
        }
        private static void bytes(Long value) {
            if (value != null && value < 0) throw new IllegalArgumentException("memory bytes must be nonnegative");
        }
    }

    public record FunctionLoad(String name, int queueDepth, int inFlight, int effectiveConcurrency,
                               int dispatchableBacklog) {
        public FunctionLoad {
            if (name == null || name.isBlank() || queueDepth < 0 || inFlight < 0
                    || effectiveConcurrency < 0 || dispatchableBacklog < 0) {
                throw new IllegalArgumentException("invalid workload measurement");
            }
        }
    }
}
