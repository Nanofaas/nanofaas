package it.unimib.datai.nanofaas.controlplane.capacity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Public, finite admission and retained-input limits for the control plane. */
@ConfigurationProperties(prefix = "nanofaas.invocation-capacity")
public final class InvocationCapacityProperties {
    private long ingressBodyBytes = 1_048_576L;
    private long executionsGlobal = 4_096;
    private long executionsPerFunction = 512;
    private long canonicalInputBytesGlobal = 134_217_728L;
    private long canonicalInputBytesPerFunction = 33_554_432L;
    private long physicalInputCopyBytesGlobal = 67_108_864L;
    private long physicalInputCopyBytesPerFunction = 16_777_216L;
    private long waitersGlobal = 8_192;
    private long waitersPerFunction = 1_024;
    private int maxInputReferences = 64;
    private int retainedInputMaxDepth = 32;
    private int retainedInputMaxContainerEntries = 16_384;
    private int retainedInputMaxVisitedNodes = 65_536;
    private long retainedInputMaxBytesPerExecution = 1_048_576L;

    public void validate() {
        positive("ingress-body-bytes", ingressBodyBytes);
        if (ingressBodyBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ingress-body-bytes must fit the WebFlux codec integer limit");
        }
        pair("executions", executionsGlobal, executionsPerFunction);
        pair("canonical-input-bytes", canonicalInputBytesGlobal, canonicalInputBytesPerFunction);
        pair("physical-input-copy-bytes", physicalInputCopyBytesGlobal, physicalInputCopyBytesPerFunction);
        pair("waiters", waitersGlobal, waitersPerFunction);
        positive("max-input-references", maxInputReferences);
        positive("retained-input-max-depth", retainedInputMaxDepth);
        positive("retained-input-max-container-entries", retainedInputMaxContainerEntries);
        positive("retained-input-max-visited-nodes", retainedInputMaxVisitedNodes);
        positive("retained-input-max-bytes-per-execution", retainedInputMaxBytesPerExecution);
        if (retainedInputMaxBytesPerExecution > canonicalInputBytesPerFunction) {
            throw new IllegalArgumentException(
                    "retained-input-max-bytes-per-execution must not exceed canonical-input-bytes-per-function");
        }
        if (retainedInputMaxBytesPerExecution > physicalInputCopyBytesPerFunction) {
            throw new IllegalArgumentException(
                    "retained-input-max-bytes-per-execution must not exceed physical-input-copy-bytes-per-function");
        }
    }

    private static void pair(String name, long global, long perFunction) {
        positive(name + "-global", global);
        positive(name + "-per-function", perFunction);
        if (perFunction > global) {
            throw new IllegalArgumentException(name + "-per-function must not exceed " + name + "-global");
        }
    }

    private static void positive(String name, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public long getIngressBodyBytes() { return ingressBodyBytes; }
    public void setIngressBodyBytes(long value) { ingressBodyBytes = value; }
    public long getExecutionsGlobal() { return executionsGlobal; }
    public void setExecutionsGlobal(long value) { executionsGlobal = value; }
    public long getExecutionsPerFunction() { return executionsPerFunction; }
    public void setExecutionsPerFunction(long value) { executionsPerFunction = value; }
    public long getCanonicalInputBytesGlobal() { return canonicalInputBytesGlobal; }
    public void setCanonicalInputBytesGlobal(long value) { canonicalInputBytesGlobal = value; }
    public long getCanonicalInputBytesPerFunction() { return canonicalInputBytesPerFunction; }
    public void setCanonicalInputBytesPerFunction(long value) { canonicalInputBytesPerFunction = value; }
    public long getPhysicalInputCopyBytesGlobal() { return physicalInputCopyBytesGlobal; }
    public void setPhysicalInputCopyBytesGlobal(long value) { physicalInputCopyBytesGlobal = value; }
    public long getPhysicalInputCopyBytesPerFunction() { return physicalInputCopyBytesPerFunction; }
    public void setPhysicalInputCopyBytesPerFunction(long value) { physicalInputCopyBytesPerFunction = value; }
    public long getWaitersGlobal() { return waitersGlobal; }
    public void setWaitersGlobal(long value) { waitersGlobal = value; }
    public long getWaitersPerFunction() { return waitersPerFunction; }
    public void setWaitersPerFunction(long value) { waitersPerFunction = value; }
    public int getMaxInputReferences() { return maxInputReferences; }
    public void setMaxInputReferences(int value) { maxInputReferences = value; }
    public int getRetainedInputMaxDepth() { return retainedInputMaxDepth; }
    public void setRetainedInputMaxDepth(int value) { retainedInputMaxDepth = value; }
    public int getRetainedInputMaxContainerEntries() { return retainedInputMaxContainerEntries; }
    public void setRetainedInputMaxContainerEntries(int value) { retainedInputMaxContainerEntries = value; }
    public int getRetainedInputMaxVisitedNodes() { return retainedInputMaxVisitedNodes; }
    public void setRetainedInputMaxVisitedNodes(int value) { retainedInputMaxVisitedNodes = value; }
    public long getRetainedInputMaxBytesPerExecution() { return retainedInputMaxBytesPerExecution; }
    public void setRetainedInputMaxBytesPerExecution(long value) { retainedInputMaxBytesPerExecution = value; }
}
