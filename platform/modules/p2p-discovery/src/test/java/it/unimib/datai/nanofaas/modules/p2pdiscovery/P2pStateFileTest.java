package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pFile.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class P2pStateFileTest {
    @TempDir Path dir;

    private List<Path> backups() throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().startsWith("p2p.yaml.corrupt")).sorted().toList();
        }
    }

    private static final String OPERATOR_YAML = """
            config:
              seeds: ["10.0.0.5:7946"]
              maxNeighbors: 4
              maxLatencyMs: 80
              peers:
                - {id: edge-3, mode: EXCLUDED}
            """;

    @Test
    void missingFileLoadsAsEmpty() {
        P2pFile f = new P2pStateFile(dir.resolve("nope.yaml")).load();
        assertThat(f.config().seeds()).isEmpty();
        assertThat(f.state().peers()).isEmpty();
        assertThat(f.state().nodeId()).isNull();
    }

    @Test
    void operatorConfigIsParsed() throws Exception {
        Path p = dir.resolve("p2p.yaml");
        Files.writeString(p, OPERATOR_YAML);
        P2pFile f = new P2pStateFile(p).load();
        assertThat(f.config().seeds()).containsExactly("10.0.0.5:7946");
        assertThat(f.config().maxNeighbors()).isEqualTo(4);
        assertThat(f.config().maxLatencyMs()).isEqualTo(80.0);
        assertThat(f.config().peers()).containsExactly(new PeerEntry("edge-3", PeerMode.EXCLUDED));
    }

    @Test
    void savingStateKeepsTheOperatorConfig() throws Exception {
        Path p = dir.resolve("p2p.yaml");
        Files.writeString(p, OPERATOR_YAML);
        var sf = new P2pStateFile(p);
        sf.saveState(new State("node-1", List.of(0.3, 1.1, 0.02), 0.4, Map.of("maxNeighbors", 6),
                Map.of("edge-7", PeerMode.FORCE_ACTIVE),
                List.of(new KnownPeer("edge-2", "10.0.0.7:7946", 12.4, List.of(0.1, 0.2, 0.3)))));
        P2pFile back = new P2pStateFile(p).load();
        assertThat(back.config().maxNeighbors()).isEqualTo(4);               // untouched
        assertThat(back.config().peers()).hasSize(1);
        assertThat(back.state().nodeId()).isEqualTo("node-1");
        assertThat(back.state().overrides()).containsEntry("maxNeighbors", 6);
        assertThat(back.state().peerModes()).containsEntry("edge-7", PeerMode.FORCE_ACTIVE);
        assertThat(back.state().peers()).extracting(KnownPeer::id).containsExactly("edge-2");
    }

    @Test
    void explicitNullOverrideSurvivesTheRoundTrip() {
        Path p = dir.resolve("p2p.yaml");
        var sf = new P2pStateFile(p);
        var overrides = new java.util.HashMap<String, Object>();
        overrides.put("maxLatencyMs", null);                                    // "threshold removed"
        sf.saveState(new State("n", null, null, overrides, Map.of(), List.of()));
        assertThat(new P2pStateFile(p).load().state().overrides()).containsKey("maxLatencyMs");
    }

    @Test
    void corruptFileDoesNotThrowAndIsNotOverwrittenByLoad() throws Exception {
        Path p = dir.resolve("p2p.yaml");
        Files.writeString(p, "config: [unclosed\n  : :");
        P2pFile f = new P2pStateFile(p).load();
        assertThat(f.config().seeds()).isEmpty();
        assertThat(Files.readString(p)).startsWith("config: [unclosed");
    }

    @Test
    void saveLeavesNoTempFileBehind() throws Exception {
        Path p = dir.resolve("p2p.yaml");
        new P2pStateFile(p).saveState(new State("n", null, null, Map.of(), Map.of(), List.of()));
        try (var s = Files.list(dir)) {
            assertThat(s.map(x -> x.getFileName().toString())).containsExactly("p2p.yaml");
        }
    }

    @Test
    void saveWorksInANewDirectory() {
        Path p = dir.resolve("sub/dir/p2p.yaml");
        new P2pStateFile(p).saveState(new State("n", null, null, Map.of(), Map.of(), List.of()));
        assertThat(p).exists();
    }

    @Test
    void savingOverACorruptFileKeepsTheOperatorsTextInABackup() throws Exception {
        Path p = dir.resolve("p2p.yaml");
        String broken = "config:\n  maxNeighbors: [oops\n";
        Files.writeString(p, broken);
        new P2pStateFile(p).saveState(new State("n", null, null, Map.of(), Map.of(), List.of()));
        assertThat(Files.readString(backups().getFirst())).isEqualTo(broken);   // nothing is lost
        assertThat(new P2pStateFile(p).load().state().nodeId()).isEqualTo("n");           // and the node can persist again
    }

    @Test
    void aMisspelledKeyIsNotSilentlyDroppedAndErasedOnTheNextSave() throws Exception {
        Path p = dir.resolve("p2p.yaml");
        String typo = "config:\n  maxNeighbours: 4\n";
        Files.writeString(p, typo);
        assertThat(new P2pStateFile(p).load().config().maxNeighbors()).isNull();   // rejected as a whole, with a warning
        new P2pStateFile(p).saveState(new State("n", null, null, Map.of(), Map.of(), List.of()));
        assertThat(Files.readString(backups().getFirst())).isEqualTo(typo);   // and the operator's text is kept
    }

    @Test
    void aSecondCorruptionDoesNotOverwriteTheFirstBackup() throws Exception {
        Path p = dir.resolve("p2p.yaml");
        var sf = new P2pStateFile(p);
        Files.writeString(p, "config: [first\n");
        sf.saveState(new State("n", null, null, Map.of(), Map.of(), List.of()));
        Files.writeString(p, "config: [second\n");
        sf.saveState(new State("n", null, null, Map.of(), Map.of(), List.of()));
        assertThat(backups()).hasSize(2).extracting(Files::readString).containsExactlyInAnyOrder("config: [first\n", "config: [second\n");
    }

    @Test
    void temporaryFilesLeftByAKilledProcessAreRemovedOnLoad() throws Exception {
        Path p = dir.resolve("p2p.yaml");
        Path stale = Files.writeString(dir.resolve("p2p.yaml123456.tmp"), "half written");
        Path unrelated = Files.writeString(dir.resolve("other.tmp"), "not ours");
        new P2pStateFile(p).removeStaleTempFiles();
        assertThat(stale).doesNotExist();
        assertThat(unrelated).exists();
    }
}
