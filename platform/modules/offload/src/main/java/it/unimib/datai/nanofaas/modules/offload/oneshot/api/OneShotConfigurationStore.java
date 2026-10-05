package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import java.util.*;
import java.util.function.BooleanSupplier;
/** Compare-and-set configuration; readers keep immutable snapshots through an auction. */
public final class OneShotConfigurationStore {
    public record Snapshot(long revision,OneShotSettings settings) {}
    public static final class RevisionConflict extends IllegalStateException {}
    private final BooleanSupplier peers,forecasts;
    private Snapshot current;
    public OneShotConfigurationStore(BooleanSupplier peers,BooleanSupplier forecasts) { this.peers=peers;this.forecasts=forecasts; }
    public synchronized Optional<Snapshot> snapshot() { return Optional.ofNullable(current); }
    public synchronized Snapshot replace(long expected,OneShotSettings settings) {
        if(expected!=(current==null?0:current.revision())) throw new RevisionConflict();
        if(!peers.getAsBoolean() || !forecasts.getAsBoolean()) throw new IllegalArgumentException("P2P and forecasting are required");
        current=new Snapshot(expected+1,Objects.requireNonNull(settings));return current;
    }
}
