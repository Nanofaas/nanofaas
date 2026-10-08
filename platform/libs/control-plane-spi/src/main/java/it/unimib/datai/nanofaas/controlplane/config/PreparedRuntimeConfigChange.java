package it.unimib.datai.nanofaas.controlplane.config;

import java.util.Map;

/**
 * A prepared, not-yet-activated runtime config change produced by
 * {@link PreparedRuntimeConfigExtension#prepare(Map)}.
 *
 * <p>{@link #commit()} is the linearization point: it may fail only before it runs any
 * irreversible activation step. Once it returns normally, the change is live and nothing
 * afterwards &mdash; including {@link #close()} or a metrics failure &mdash; may undo it
 * or report the update as failed.</p>
 */
public interface PreparedRuntimeConfigChange extends AutoCloseable {

    /** The namespace snapshot that will be visible once {@link #commit()} has run. */
    Map<String, Object> snapshotAfterCommit();

    /** Activates the prepared change. Failure is only permitted before activation. */
    void commit();

    /**
     * Releases any resources held by this prepared change. Called whether {@link #commit()}
     * ran or not; must never throw and must never undo a commit that already happened.
     */
    @Override
    void close();
}
