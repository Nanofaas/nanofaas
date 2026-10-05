package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import org.springframework.lang.Nullable;

/**
 * Sync invocation outcome: the response plus the remote target when the
 * invocation was offloaded (surfaced as the X-NanoFaaS-Offloaded header).
 */
public record SyncInvocation(InvocationResponse response, @Nullable String offloadedTarget,@Nullable String executionNode) {
    public SyncInvocation(InvocationResponse response,String target) { this(response,target,null); }

    public static SyncInvocation local(InvocationResponse response) {
        return new SyncInvocation(response, null);
    }
}
