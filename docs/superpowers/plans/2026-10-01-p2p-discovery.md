# p2p-discovery Implementation Plan

Historical plan. Integration with current main uses `module.properties`, Spring Boot
auto-configuration and a module OpenAPI fragment instead of the SPI described below.
See the module README for current build selection and runtime participation controls.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Optional control-plane module that discovers other nanofaas nodes (scalecube SWIM), measures RTT + Vivaldi coordinates, selects "active neighbors" (max count, latency threshold, manual modes), exposes send/request/broadcast primitives, an admin API, and a YAML config+state file for fast restarts.

**Architecture:** One module `platform/modules/p2p-discovery` loaded via the `ControlPlaneModule` SPI like `offload`. Pure logic (`NeighborSelector`, `Vivaldi`, `RttWindow`, `PeerTable`, `P2pStateFile`) is separate from I/O (`PeerCluster`, the only class touching scalecube). `P2pService` (a `SmartLifecycle`) wires them; it is a no-op unless `nanofaas.p2p.enabled=true`.

**Tech Stack:** Java 25, Spring Boot 4.1.0 (WebFlux, Jackson 3 `tools.jackson`), scalecube-cluster 2.7.1 + scalecube-transport-netty 2.7.1, Micrometer, JUnit 5 + AssertJ + ArchUnit, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-01-p2p-discovery-design.md`

## Global Constraints

- Package root `it.unimib.datai.nanofaas`; module package `it.unimib.datai.nanofaas.modules.p2pdiscovery`; 4-space indentation.
- Module dir `platform/modules/p2p-discovery` (auto-included by `settings.gradle` as `:control-plane-modules:p2p-discovery`; modules default to `all`, so the module ships in every build and MUST be inert when disabled).
- Disabled by default: `nanofaas.p2p.enabled=false`. Admin API gated by `nanofaas.p2p.admin.enabled` (default false), via `@ConditionalOnProperty` on the controller bean exactly as `AdminRuntimeConfigController`.
- Only `PeerCluster` may import `io.scalecube..` (ArchUnit-enforced). The module must not depend on other `..modules..` packages (SPI isolation, same rule as `offload`).
- Tests use `*Test.java`; run with `./gradlew :control-plane-modules:p2p-discovery:test`.
- No Claude trailer in commit messages (repo history was rewritten; see project memory). Do NOT `git add` `CLAUDE.md`/`AGENTS.md`: they carry the user's uncommitted edits.
- Messages: application payload is opaque `byte[]`; the topic travels in a scalecube message header; topics starting `p2p.` are reserved. Cluster-internal (SWIM) messages keep scalecube's default codec.
- Heavy dependency: native-image impact must be measured (Task 10) — do not skip.

## Spec deviations (decided while planning; flag to the user)

1. Spec said "Codec JSON". scalecube's SWIM system messages require its default (JDK) codec, so only the *application* payload is codec-free (opaque `byte[]`, topic in a header). Task 4 notes this; the spec line is amended in Task 11.
2. Spec said the `config` section is preserved "byte for byte". Re-serializing YAML drops comments. Semantics are preserved, comments are not; documented in the README (Task 11).

## Review Focus

Failure modes the spec implies but earlier tasks do not exercise on their own; each has a test in the owning task:

1. State file with unknown/garbage content, or half-written (kill during write) → boot must not fail, atomic rename (Task 7).
2. `maxNeighbors=0`, negative, or `maxLatencyMs<=0` via PATCH → 400, not silently accepted (Task 8).
3. Peer id appears in file `peers` list and in API modes with different values → API wins; clearing falls back to file (Tasks 5, 8).
4. A peer leaves and rejoins with a new address → table must follow the id, not keep the stale address (Task 5).
5. `request` to a peer that is alive but not active, and to an unknown id → explicit error, not a hang (Task 6).
6. Two nodes started with the same `nodeId` → scalecube accepts it silently; detect and log an error (Task 9).

## File Structure

```
platform/modules/p2p-discovery/
  build.gradle
  README.md
  src/main/java/it/unimib/datai/nanofaas/modules/p2pdiscovery/
    P2pModule.java            SPI entry
    P2pConfiguration.java     beans, conditional admin controller
    P2pProperties.java        nanofaas.p2p.*
    PeerMode.java             AUTO | FORCE_ACTIVE | EXCLUDED
    NeighborSelector.java     pure selection rules
    Vivaldi.java              pure coordinates
    RttWindow.java            pure median window
    PeerTable.java            peer state + recompute
    P2pSettings.java          layered effective settings (yml/file/overrides)
    P2pStateFile.java         YAML load / atomic save (+ P2pFile records)
    PeerCluster.java          scalecube wrapper (ONLY scalecube user)
    PeerMessaging.java        send/request/broadcast/subscribe
    LatencyMonitor.java       ping loop → RTT + Vivaldi
    P2pService.java           SmartLifecycle orchestration + metrics
    P2pAdminController.java   /v1/admin/p2p
  src/main/resources/META-INF/services/it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule
  src/test/java/.../p2pdiscovery/   one *Test per class + architecture/ArchitectureTest
```

---

### Task 1: Module skeleton

**Files:**
- Create: `platform/modules/p2p-discovery/build.gradle`
- Create: `platform/modules/p2p-discovery/src/main/resources/META-INF/services/it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule`
- Create: `.../p2pdiscovery/P2pModule.java`, `P2pConfiguration.java`, `P2pProperties.java`, `PeerMode.java`
- Test: `.../p2pdiscovery/architecture/ArchitectureTest.java`, `.../p2pdiscovery/P2pPropertiesTest.java`

**Interfaces:**
- Produces: `PeerMode { AUTO, FORCE_ACTIVE, EXCLUDED }`; `P2pProperties` record (below); module discoverable by `ServiceLoader`.

- [ ] **Step 1: Write the failing tests**

`P2pPropertiesTest.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class P2pPropertiesTest {
    @Test
    void defaultsAreInertAndUnbounded() {
        P2pProperties p = new P2pProperties(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(p.enabled()).isFalse();
        assertThat(p.admin().enabled()).isFalse();
        assertThat(p.port()).isZero();
        assertThat(p.seeds()).isEmpty();
        assertThat(p.maxNeighbors()).isNull();
        assertThat(p.maxLatencyMs()).isNull();
        assertThat(p.pingInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.pingTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.stateFile()).isNull();
    }
}
```

`architecture/ArchitectureTest.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "it.unimib.datai.nanofaas.modules.p2pdiscovery..")
class ArchitectureTest {

    @ArchTest
    static final ArchRule does_not_depend_on_other_modules =
            noClasses()
                    .that().resideInAPackage("..modules.p2pdiscovery..")
                    .should().dependOnClassesThat(
                            resideInAPackage("..modules..")
                                    .and(DescribedPredicate.not(resideInAPackage("..modules.p2pdiscovery.."))))
                    .as("module must not depend on other modules (SPI isolation)");

    @ArchTest
    static final ArchRule only_peer_cluster_touches_scalecube =
            noClasses()
                    .that().doNotHaveSimpleName("PeerCluster")
                    .and().resideInAPackage("..modules.p2pdiscovery..")
                    .should().dependOnClassesThat(resideInAPackage("io.scalecube.."))
                    .as("scalecube is confined to PeerCluster so the engine stays replaceable");
}
```

- [ ] **Step 2: Create `build.gradle`** (then confirm tests fail to compile — classes missing)

```groovy
plugins {
    id 'java-library'
    id 'io.spring.dependency-management'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:${springBootVersion}"
    }
}

dependencies {
    implementation project(':common')
    implementation project(':control-plane')

    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'io.micrometer:micrometer-core'
    implementation 'tools.jackson.dataformat:jackson-dataformat-yaml'

    // scalecube pins reactor-netty 1.0.32 / netty 4.1.92; the Boot BOM above overrides them
    // (verified by the 2026-10-01 spike on Reactor Netty 1.3.6 / Netty 4.2.15).
    implementation 'io.scalecube:scalecube-cluster:2.7.1'
    implementation 'io.scalecube:scalecube-transport-netty:2.7.1'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-webtestclient'
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.5.0'
    testImplementation 'io.projectreactor:reactor-test'
    testImplementation 'org.awaitility:awaitility:4.3.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

Run: `./gradlew :control-plane-modules:p2p-discovery:compileTestJava`
Expected: FAIL — `P2pProperties` not found.

- [ ] **Step 3: Write the minimal implementation**

`PeerMode.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

public enum PeerMode { AUTO, FORCE_ACTIVE, EXCLUDED }
```

`P2pProperties.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param enabled      master switch; the module is inert when false
 * @param nodeId       stable id of this node; generated and stored in the state file when absent
 * @param port         cluster transport port (0 = ephemeral)
 * @param externalHost address advertised to peers (containers/NAT); null = auto
 * @param seeds        host:port of nodes to join through
 * @param maxNeighbors max active neighbors; null = unlimited
 * @param maxLatencyMs latency threshold; null = filter not applied
 * @param stateFile    YAML file with operator {@code config} and node {@code state}; null = none
 */
@ConfigurationProperties(prefix = "nanofaas.p2p")
public record P2pProperties(
        Boolean enabled,
        String nodeId,
        Integer port,
        String externalHost,
        List<String> seeds,
        Integer maxNeighbors,
        Double maxLatencyMs,
        Duration pingInterval,
        Duration pingTimeout,
        String stateFile,
        Admin admin
) {
    public record Admin(Boolean enabled) {
        public Admin {
            if (enabled == null) {
                enabled = false;
            }
        }
    }

    public P2pProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (port == null) {
            port = 0;
        }
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
        if (pingInterval == null) {
            pingInterval = Duration.ofSeconds(1);
        }
        if (pingTimeout == null) {
            pingTimeout = Duration.ofSeconds(2);
        }
        if (admin == null) {
            admin = new Admin(null);
        }
    }
}
```

`P2pModule.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class P2pModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(P2pConfiguration.class);
    }
}
```

`P2pConfiguration.java` (grows in later tasks):
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(P2pProperties.class)
public class P2pConfiguration {
}
```

Services file content (one line):
`it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pModule`

- [ ] **Step 4: Run tests**

Run: `./gradlew :control-plane-modules:p2p-discovery:test`
Expected: PASS (2 ArchUnit rules + properties test).

- [ ] **Step 5: Verify the module is inert in the control plane**

Run: `./gradlew :control-plane:test --no-parallel`
Expected: PASS (module is on the runtime classpath by default and does nothing).

- [ ] **Step 6: Commit**

```bash
git add platform/modules/p2p-discovery
git commit -m "feat(p2p): module skeleton, properties and ArchUnit isolation"
```

---

### Task 2: NeighborSelector (pure rules)

**Files:**
- Create: `.../p2pdiscovery/NeighborSelector.java`
- Test: `.../p2pdiscovery/NeighborSelectorTest.java`

**Interfaces:**
- Produces:
  - `NeighborSelector.Settings(Integer maxNeighbors, Double maxLatencyMs)`
  - `NeighborSelector.Candidate(String id, PeerMode mode, Double rttMs, Double orderHintMs, boolean wasActive)` — `rttMs` = measured median (null = never measured this run); `orderHintMs` = restored RTT, used **only for ordering, never for the threshold**
  - `NeighborSelector.Decision(boolean active, String reason)`
  - `Map<String, Decision> select(List<Candidate> peers, Settings settings)`
  - reasons: `excluded`, `forced`, `no-measurement`, `latency`, `selected`, `over-max-neighbors`

- [ ] **Step 1: Write the failing tests**

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Candidate;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Decision;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NeighborSelectorTest {
    private final NeighborSelector selector = new NeighborSelector();

    private static Candidate c(String id, PeerMode mode, Double rtt) {
        return new Candidate(id, mode, rtt, null, false);
    }

    private static Decision d(Map<String, Decision> m, String id) {
        return m.get(id);
    }

    @Test
    void noThresholdNoMaxMeansEveryoneActiveEvenUnmeasured() {
        var r = selector.select(List.of(c("a", PeerMode.AUTO, 500.0), c("b", PeerMode.AUTO, null)),
                new Settings(null, null));
        assertThat(d(r, "a").active()).isTrue();
        assertThat(d(r, "b").active()).isTrue();
    }

    @Test
    void excludedIsNeverActiveEvenWhenForcedByNothingElse() {
        var r = selector.select(List.of(c("a", PeerMode.EXCLUDED, 1.0)), new Settings(null, null));
        assertThat(d(r, "a")).isEqualTo(new Decision(false, "excluded"));
    }

    @Test
    void forcedIgnoresThresholdAndMax() {
        var r = selector.select(List.of(c("a", PeerMode.FORCE_ACTIVE, 999.0), c("b", PeerMode.AUTO, 1.0)),
                new Settings(1, 10.0));
        assertThat(d(r, "a")).isEqualTo(new Decision(true, "forced"));
        assertThat(d(r, "b").active()).isFalse();           // forced peer consumed the only slot
        assertThat(d(r, "b").reason()).isEqualTo("over-max-neighbors");
    }

    @Test
    void thresholdSetCutsSlowAndUnmeasuredPeers() {
        var r = selector.select(List.of(c("fast", PeerMode.AUTO, 20.0), c("slow", PeerMode.AUTO, 90.0),
                c("new", PeerMode.AUTO, null)), new Settings(null, 50.0));
        assertThat(d(r, "fast").active()).isTrue();
        assertThat(d(r, "slow")).isEqualTo(new Decision(false, "latency"));
        assertThat(d(r, "new")).isEqualTo(new Decision(false, "no-measurement"));
    }

    @Test
    void restoredRttNeverSatisfiesTheThreshold() {
        var restored = new Candidate("p", PeerMode.AUTO, null, 5.0, false);
        var r = selector.select(List.of(restored), new Settings(null, 50.0));
        assertThat(d(r, "p")).isEqualTo(new Decision(false, "no-measurement"));
    }

    @Test
    void maxNeighborsKeepsLowestRttUnmeasuredLastRestoredHintOrders() {
        var r = selector.select(List.of(c("a", PeerMode.AUTO, 30.0), c("b", PeerMode.AUTO, 10.0),
                new Candidate("c", PeerMode.AUTO, null, 20.0, false), c("d", PeerMode.AUTO, null)),
                new Settings(2, null));
        assertThat(d(r, "b").active()).isTrue();
        assertThat(d(r, "c").active()).isTrue();            // hint 20 beats measured 30 for ordering
        assertThat(d(r, "a")).isEqualTo(new Decision(false, "over-max-neighbors"));
        assertThat(d(r, "d").active()).isFalse();
    }

    @Test
    void maxNeighborsZeroActivatesOnlyForced() {
        var r = selector.select(List.of(c("a", PeerMode.AUTO, 1.0), c("f", PeerMode.FORCE_ACTIVE, 1.0)),
                new Settings(0, null));
        assertThat(d(r, "a").active()).isFalse();
        assertThat(d(r, "f").active()).isTrue();
    }

    @Test
    void hysteresisKeepsActivePeerJustAboveThreshold() {
        var wasActive = new Candidate("a", PeerMode.AUTO, 52.0, null, true);   // 4% over 50, within +10%
        var wasIdle = new Candidate("b", PeerMode.AUTO, 52.0, null, false);
        var r = selector.select(List.of(wasActive, wasIdle), new Settings(null, 50.0));
        assertThat(d(r, "a").active()).isTrue();
        assertThat(d(r, "b")).isEqualTo(new Decision(false, "latency"));
    }

    @Test
    void activePeerFarAboveThresholdIsDropped() {
        var r = selector.select(List.of(new Candidate("a", PeerMode.AUTO, 60.0, null, true)),
                new Settings(null, 50.0));
        assertThat(d(r, "a")).isEqualTo(new Decision(false, "latency"));
    }

    @Test
    void tieBreaksById() {
        var r = selector.select(List.of(c("b", PeerMode.AUTO, 10.0), c("a", PeerMode.AUTO, 10.0)),
                new Settings(1, null));
        assertThat(d(r, "a").active()).isTrue();
        assertThat(d(r, "b").active()).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :control-plane-modules:p2p-discovery:test --tests '*NeighborSelectorTest'`
Expected: FAIL — `NeighborSelector` not found.

- [ ] **Step 3: Implement**

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure neighbor-selection rules; see the design spec, section "Selezione dei vicini". */
public final class NeighborSelector {
    /** A peer already active stays active up to threshold * (1 + HYSTERESIS). */
    static final double HYSTERESIS = 0.10;

    public record Settings(Integer maxNeighbors, Double maxLatencyMs) {}

    public record Candidate(String id, PeerMode mode, Double rttMs, Double orderHintMs, boolean wasActive) {}

    public record Decision(boolean active, String reason) {}

    public Map<String, Decision> select(List<Candidate> peers, Settings settings) {
        Map<String, Decision> out = new HashMap<>();
        List<Candidate> pool = new ArrayList<>();
        int forced = 0;
        for (Candidate p : peers) {
            if (p.mode() == PeerMode.EXCLUDED) {
                out.put(p.id(), new Decision(false, "excluded"));
            } else if (p.mode() == PeerMode.FORCE_ACTIVE) {
                out.put(p.id(), new Decision(true, "forced"));
                forced++;
            } else if (settings.maxLatencyMs() != null && p.rttMs() == null) {
                out.put(p.id(), new Decision(false, "no-measurement"));
            } else if (settings.maxLatencyMs() != null && p.rttMs() > limit(p, settings.maxLatencyMs())) {
                out.put(p.id(), new Decision(false, "latency"));
            } else {
                pool.add(p);
            }
        }
        pool.sort(Comparator
                .comparingDouble(NeighborSelector::order)
                .thenComparing(Candidate::id));
        int capacity = settings.maxNeighbors() == null ? Integer.MAX_VALUE : Math.max(0, settings.maxNeighbors() - forced);
        for (int i = 0; i < pool.size(); i++) {
            out.put(pool.get(i).id(), i < capacity ? new Decision(true, "selected")
                    : new Decision(false, "over-max-neighbors"));
        }
        return out;
    }

    private static double limit(Candidate p, double max) {
        return p.wasActive() ? max * (1 + HYSTERESIS) : max;
    }

    /** Measured RTT first, restored hint second, unknown last. */
    private static double order(Candidate p) {
        if (p.rttMs() != null) return p.rttMs();
        if (p.orderHintMs() != null) return p.orderHintMs();
        return Double.MAX_VALUE;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :control-plane-modules:p2p-discovery:test --tests '*NeighborSelectorTest'`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add platform/modules/p2p-discovery
git commit -m "feat(p2p): NeighborSelector rules with latency threshold and hysteresis"
```

---

### Task 3: Vivaldi and RttWindow (pure)

**Files:**
- Create: `.../p2pdiscovery/Vivaldi.java`, `.../p2pdiscovery/RttWindow.java`
- Test: `.../p2pdiscovery/VivaldiTest.java`, `.../p2pdiscovery/RttWindowTest.java`

**Interfaces:**
- Produces:
  - `Vivaldi.Coord(double x, double y, double z)` with `double distanceTo(Coord)`, `List<Double> toList()`, `static Coord fromList(List<Double>)` (null/short list → origin)
  - `Vivaldi(long seed)`; `synchronized Coord coord()`; `synchronized double error()`; `synchronized void restore(Coord c, double error)`; `synchronized void update(double rttMs, Coord remote, double remoteError)`
  - `RttWindow(int size)`; `void add(double rttMs)`; `Double median()` (null when empty)

- [ ] **Step 1: Write failing tests**

`RttWindowTest.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RttWindowTest {
    @Test
    void emptyHasNoMedian() {
        assertThat(new RttWindow(5).median()).isNull();
    }

    @Test
    void medianOfOddAndEven() {
        var w = new RttWindow(5);
        w.add(10); w.add(30); w.add(20);
        assertThat(w.median()).isEqualTo(20.0);
        w.add(40);
        assertThat(w.median()).isEqualTo(25.0);
    }

    @Test
    void oldestSampleIsEvicted() {
        var w = new RttWindow(3);
        w.add(1000); w.add(10); w.add(10); w.add(10);   // the 1000 outlier falls out
        assertThat(w.median()).isEqualTo(10.0);
    }
}
```

`VivaldiTest.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VivaldiTest {
    @Test
    void twoNodesConvergeToTheMeasuredRtt() {
        Vivaldi a = new Vivaldi(1), b = new Vivaldi(2);
        for (int i = 0; i < 300; i++) {
            a.update(50, b.coord(), b.error());
            b.update(50, a.coord(), a.error());
        }
        assertThat(a.coord().distanceTo(b.coord())).isCloseTo(50.0, within(3.0));
    }

    @Test
    void triangleEmbedsAllThreeDistances() {
        Vivaldi[] n = {new Vivaldi(1), new Vivaldi(2), new Vivaldi(3)};
        double[][] rtt = {{0, 30, 40}, {30, 0, 50}, {40, 50, 0}};
        for (int it = 0; it < 600; it++)
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    if (i != j) n[i].update(rtt[i][j], n[j].coord(), n[j].error());
        for (int i = 0; i < 3; i++)
            for (int j = i + 1; j < 3; j++)
                assertThat(n[i].coord().distanceTo(n[j].coord())).isCloseTo(rtt[i][j], within(6.0));
    }

    @Test
    void coincidentNodesAreSeparatedNotNaN() {
        Vivaldi a = new Vivaldi(1);
        a.update(40, new Coord(0, 0, 0), 1.0);   // both at origin: direction must be chosen, not NaN
        assertThat(Double.isNaN(a.coord().x())).isFalse();
        assertThat(a.coord().distanceTo(new Coord(0, 0, 0))).isGreaterThan(0);
    }

    @Test
    void nonPositiveRttIsIgnored() {
        Vivaldi a = new Vivaldi(1);
        Coord before = a.coord();
        a.update(0, new Coord(1, 1, 1), 1.0);
        a.update(-5, new Coord(1, 1, 1), 1.0);
        assertThat(a.coord()).isEqualTo(before);
    }

    @Test
    void coordRoundTripsThroughAList() {
        Coord c = new Coord(0.3, 1.1, 0.02);
        assertThat(Coord.fromList(c.toList())).isEqualTo(c);
        assertThat(Coord.fromList(null)).isEqualTo(new Coord(0, 0, 0));
        assertThat(Coord.fromList(List.of(1.0))).isEqualTo(new Coord(0, 0, 0));
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*VivaldiTest' --tests '*RttWindowTest'` → FAIL (classes missing).

- [ ] **Step 3: Implement**

`RttWindow.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Sliding window of RTT samples; the median resists single outliers. Not thread-safe. */
final class RttWindow {
    private final int size;
    private final ArrayDeque<Double> samples = new ArrayDeque<>();

    RttWindow(int size) {
        this.size = size;
    }

    void add(double rttMs) {
        if (samples.size() == size) {
            samples.removeFirst();
        }
        samples.addLast(rttMs);
    }

    Double median() {
        if (samples.isEmpty()) return null;
        double[] s = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }
}
```

`Vivaldi.java` (Dabek et al., adaptive timestep, 3 dimensions, no height):
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.List;
import java.util.Random;

/** Vivaldi network coordinates. The only mutable state is guarded by this instance's monitor. */
public final class Vivaldi {
    private static final double CC = 0.25;   // timestep constant
    private static final double CE = 0.25;   // error-weight constant
    private static final double MAX_ERROR = 1.0;

    public record Coord(double x, double y, double z) {
        public double distanceTo(Coord o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public List<Double> toList() {
            return List.of(x, y, z);
        }

        public static Coord fromList(List<Double> l) {
            return l == null || l.size() < 3 ? new Coord(0, 0, 0) : new Coord(l.get(0), l.get(1), l.get(2));
        }
    }

    private final Random random;
    private Coord coord = new Coord(0, 0, 0);
    private double error = MAX_ERROR;

    public Vivaldi(long seed) {
        this.random = new Random(seed);
    }

    public synchronized Coord coord() {
        return coord;
    }

    public synchronized double error() {
        return error;
    }

    public synchronized void restore(Coord c, double err) {
        this.coord = c;
        this.error = Math.min(MAX_ERROR, Math.max(0.0, err));
    }

    public synchronized void update(double rttMs, Coord remote, double remoteError) {
        if (!(rttMs > 0) || Double.isNaN(rttMs)) {
            return;
        }
        double w = error / Math.max(error + remoteError, 1e-9);
        double dist = coord.distanceTo(remote);
        double sampleError = Math.abs(dist - rttMs) / rttMs;
        error = Math.min(MAX_ERROR, Math.max(0.0, sampleError * CE * w + error * (1 - CE * w)));
        double[] dir = unit(coord, remote, dist);
        double force = CC * w * (rttMs - dist);
        coord = new Coord(coord.x() + force * dir[0], coord.y() + force * dir[1], coord.z() + force * dir[2]);
    }

    /** Unit vector from remote towards local; random when the two coincide. */
    private double[] unit(Coord local, Coord remote, double dist) {
        if (dist > 1e-9) {
            return new double[]{(local.x() - remote.x()) / dist, (local.y() - remote.y()) / dist,
                    (local.z() - remote.z()) / dist};
        }
        double x = random.nextGaussian(), y = random.nextGaussian(), z = random.nextGaussian();
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }
}
```

- [ ] **Step 4: Run to verify pass** — same command → PASS (8 tests). If `triangleEmbedsAllThreeDistances` is flaky-tight, raise iterations (not tolerance past 8 ms): it is deterministic (seeded).

- [ ] **Step 5: Commit** — `git add platform/modules/p2p-discovery && git commit -m "feat(p2p): Vivaldi coordinates and RTT median window"`

---

### Task 4: PeerCluster (scalecube wrapper)

**Files:**
- Create: `.../p2pdiscovery/PeerCluster.java`
- Test: `.../p2pdiscovery/PeerClusterTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `PeerCluster(String nodeId, int port, String externalHost, List<String> seeds)`
  - `Mono<Void> start()`; `void close()`; `String id()`; `String address()` (valid after `start()`)
  - `Flux<MemberEvent> events()`; `record MemberEvent(Type type, String id, String address)`; `enum Type { ADDED, REMOVED }` (LEAVING is mapped to REMOVED — leaving nodes must stop being used immediately)
  - `Collection<MemberEvent>` is not needed: current members arrive as `ADDED` events replayed to late subscribers? **No** — `events()` is hot; callers must call `members()`: `List<MemberEvent> members()` (type ADDED) for the current view
  - `Mono<Void> send(String address, String topic, byte[] payload)`
  - `Mono<byte[]> request(String address, String topic, byte[] payload, Duration timeout)`
  - `void handle(String topic, Handler handler)`; `interface Handler { Mono<byte[]> onMessage(String senderAddress, byte[] payload); }` — for one-way `send` the returned Mono's value is ignored; for `request` its value is the reply (empty Mono → empty reply `new byte[0]`)

**Facts established by the spike (do not re-derive):** `Cluster` 2.7.1 has no `send`; capture the `Transport` through `ClusterImpl.transport(t -> t.transportFactory(cfg -> ...))`; the receiver must put its own address in the `sender` header or replies cannot be routed; `requestResponse` matches the reply by `correlationId`; responses also reach `ClusterMessageHandler.onMessage` and must be ignored there.

- [ ] **Step 1: Write the failing test** (real sockets on loopback, ephemeral ports)

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerCluster.MemberEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PeerClusterTest {
    private final List<PeerCluster> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        started.forEach(PeerCluster::close);
    }

    private PeerCluster node(String id, List<String> seeds) {
        PeerCluster c = new PeerCluster(id, 0, "127.0.0.1", seeds);
        c.start().block(Duration.ofSeconds(10));
        started.add(c);
        return c;
    }

    @Test
    void threeNodesDiscoverEachOtherThroughOneSeed() {
        PeerCluster a = node("a", List.of());
        PeerCluster b = node("b", List.of(a.address()));
        PeerCluster c = node("c", List.of(a.address()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(a.members()).extracting(MemberEvent::id).containsExactlyInAnyOrder("b", "c");
            assertThat(c.members()).extracting(MemberEvent::id).containsExactlyInAnyOrder("a", "b");
        });
    }

    @Test
    void requestGetsTheHandlerReplyAndSendIsDelivered() {
        PeerCluster a = node("a", List.of());
        PeerCluster b = node("b", List.of(a.address()));
        List<String> got = new CopyOnWriteArrayList<>();
        b.handle("echo", (sender, payload) -> {
            got.add(new String(payload, StandardCharsets.UTF_8));
            return Mono.just("re:".getBytes(StandardCharsets.UTF_8));
        });
        await().until(() -> !a.members().isEmpty());

        byte[] reply = a.request(b.address(), "echo", "hi".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3))
                .block(Duration.ofSeconds(5));
        assertThat(new String(reply, StandardCharsets.UTF_8)).isEqualTo("re:");

        a.send(b.address(), "echo", "one-way".getBytes(StandardCharsets.UTF_8)).block(Duration.ofSeconds(3));
        await().untilAsserted(() -> assertThat(got).containsExactly("hi", "one-way"));
    }

    @Test
    void closedNodeIsReportedRemoved() {
        PeerCluster a = node("a", List.of());
        PeerCluster b = node("b", List.of(a.address()));
        List<MemberEvent> events = new CopyOnWriteArrayList<>();
        a.events().subscribe(events::add);
        await().until(() -> !a.members().isEmpty());
        b.close();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(events).anySatisfy(e -> {
                    assertThat(e.id()).isEqualTo("b");
                    assertThat(e.type()).isEqualTo(PeerCluster.Type.REMOVED);
                }));
        assertThat(a.members()).isEmpty();
    }

    @Test
    void requestToUnreachableAddressFailsInsteadOfHanging() {
        PeerCluster a = node("a", List.of());
        var err = a.request("127.0.0.1:1", "echo", new byte[0], Duration.ofSeconds(1))
                .map(x -> "ok").onErrorReturn("err").block(Duration.ofSeconds(5));
        assertThat(err).isEqualTo("err");
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*PeerClusterTest'` → FAIL (class missing).

- [ ] **Step 3: Implement** (`javap -cp <scalecube jars> 'io.scalecube.cluster.transport.api.Message$Builder'` confirmed `header(String,String)`, `correlationId`, `sender`, `data`, `build`)

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.ClusterMessageHandler;
import io.scalecube.cluster.membership.MembershipEvent;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.Transport;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** The only class that knows scalecube. Everything above sees ids, addresses, topics and byte[]. */
public final class PeerCluster implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PeerCluster.class);
    private static final String Q_MSG = "p2p.msg";
    private static final String Q_REQ = "p2p.req";
    private static final String Q_RES = "p2p.res";
    private static final String H_TOPIC = "topic";

    public enum Type { ADDED, REMOVED }

    public record MemberEvent(Type type, String id, String address) {}

    @FunctionalInterface
    public interface Handler {
        Mono<byte[]> onMessage(String senderAddress, byte[] payload);
    }

    private final String nodeId;
    private final int port;
    private final String externalHost;
    private final List<String> seeds;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Sinks.Many<MemberEvent> events = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<Transport> transport = new AtomicReference<>();
    private volatile Cluster cluster;

    public PeerCluster(String nodeId, int port, String externalHost, List<String> seeds) {
        this.nodeId = nodeId;
        this.port = port;
        this.externalHost = externalHost;
        this.seeds = List.copyOf(seeds);
    }

    public Mono<Void> start() {
        return new ClusterImpl()
                .config(c -> {
                    c = c.memberId(nodeId).memberAlias(nodeId);
                    return externalHost == null ? c : c.externalHost(externalHost);
                })
                .transport(t -> t.port(port).transportFactory(cfg -> {
                    Transport tr = new TcpTransportFactory().createTransport(cfg);
                    transport.set(tr);
                    return tr;
                }))
                .membership(m -> m.seedMembers(seeds))
                .handler(c -> new ClusterMessageHandler() {
                    @Override
                    public void onMessage(Message m) {
                        dispatch(m);
                    }

                    @Override
                    public void onMembershipEvent(MembershipEvent e) {
                        Type type = switch (e.type()) {
                            case ADDED -> Type.ADDED;
                            case REMOVED, LEAVING -> Type.REMOVED;
                            case UPDATED -> null;
                        };
                        if (type != null) {
                            events.tryEmitNext(new MemberEvent(type, e.member().id(), e.member().address()));
                        }
                    }
                })
                .start()
                .doOnNext(c -> cluster = c)
                .then();
    }

    public String id() {
        return nodeId;
    }

    public String address() {
        return cluster.address();
    }

    public Flux<MemberEvent> events() {
        return events.asFlux();
    }

    public List<MemberEvent> members() {
        return cluster.otherMembers().stream()
                .map(m -> new MemberEvent(Type.ADDED, m.id(), m.address())).toList();
    }

    public void handle(String topic, Handler handler) {
        if (handlers.putIfAbsent(topic, handler) != null) {
            throw new IllegalStateException("handler already registered for topic " + topic);
        }
    }

    public Mono<Void> send(String address, String topic, byte[] payload) {
        return transport.get().send(address, message(Q_MSG, topic, null, payload));
    }

    public Mono<byte[]> request(String address, String topic, byte[] payload, Duration timeout) {
        String cid = UUID.randomUUID().toString();
        return transport.get().requestResponse(address, message(Q_REQ, topic, cid, payload))
                .timeout(timeout)
                .map(m -> (byte[]) m.data());
    }

    @Override
    public void close() {
        Cluster c = cluster;
        if (c != null) {
            c.shutdown();
            c.onShutdown().block(Duration.ofSeconds(5));
        }
    }

    private Message message(String qualifier, String topic, String correlationId, byte[] payload) {
        Message.Builder b = Message.withQualifier(qualifier)
                .header(H_TOPIC, topic)
                .sender(address())
                .data(payload);
        if (correlationId != null) {
            b.correlationId(correlationId);
        }
        return b.build();
    }

    private void dispatch(Message m) {
        String q = m.qualifier();
        if (Q_RES.equals(q) || (!Q_MSG.equals(q) && !Q_REQ.equals(q))) {
            return;   // replies are consumed by Transport.requestResponse; the rest is not ours
        }
        Handler h = handlers.get(m.header(H_TOPIC));
        if (h == null) {
            log.debug("no handler for topic {}", m.header(H_TOPIC));
            return;
        }
        byte[] in = m.data() == null ? new byte[0] : m.data();
        Mono<byte[]> out = h.onMessage(m.sender(), in);
        if (Q_REQ.equals(q)) {
            out.defaultIfEmpty(new byte[0])
                    .flatMap(reply -> transport.get().send(m.sender(), Message.withQualifier(Q_RES)
                            .correlationId(m.correlationId()).sender(address()).data(reply).build()))
                    .doOnError(e -> log.warn("reply to {} failed: {}", m.sender(), e.toString()))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
        } else {
            out.doOnError(e -> log.warn("handler for {} failed: {}", m.header(H_TOPIC), e.toString()))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
        }
    }
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*PeerClusterTest'` → PASS. Expect ~10–15 s (graceful leave ≈ 10 s to `REMOVED` with scalecube defaults, hence the 20 s await). Netty `Unsafe` warnings on stderr are harmless.
  - If compilation complains about `MembershipEvent.Type` switch exhaustiveness, add the missing constant; if Reactor Netty-http/brave/quic jars bloat the classpath, try excluding `reactor-netty-http-brave`, `reactor-netty-incubator-quic`, `zipkin*`, `brave*` on the scalecube dependency in `build.gradle` and re-run (keep exclusions only if tests still pass).

- [ ] **Step 5: Run the architecture test** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*ArchitectureTest'` → PASS (scalecube confined to `PeerCluster`).

- [ ] **Step 6: Commit** — `git add platform/modules/p2p-discovery && git commit -m "feat(p2p): PeerCluster wrapping scalecube (membership, send, request)"`

---

### Task 5: PeerTable and P2pSettings

**Files:**
- Create: `.../p2pdiscovery/PeerTable.java`, `.../p2pdiscovery/P2pSettings.java`
- Test: `.../p2pdiscovery/PeerTableTest.java`, `.../p2pdiscovery/P2pSettingsTest.java`

**Interfaces:**
- Consumes: `NeighborSelector` (Task 2), `RttWindow`, `Vivaldi.Coord` (Task 3), `PeerMode` (Task 1).
- Produces:
  - `P2pSettings(Integer maxNeighbors, Double maxLatencyMs)` (base from yml/file) with `NeighborSelector.Settings effective()`, `Map<String,Object> overrides()`, `void patch(Map<String,Object>)` (throws `IllegalArgumentException` with a message on invalid input), `void clearOverrides()`, `void loadOverrides(Map<String,Object>)`, `void setBase(Integer, Double)`
  - `PeerTable(Supplier<NeighborSelector.Settings> settings)` with:
    - `record Peer(String id, String address, PeerMode mode, Double rttMs, List<Double> coord, boolean active, String reason)`
    - `void upsert(String id, String address)`; `void remove(String id)`; `void recordRtt(String id, double rttMs, Coord coord)`; `void restore(String id, String address, Double rttHintMs, Coord coord)`
    - `void setOperatorMode(String id, PeerMode mode)`; `void setApiMode(String id, PeerMode modeOrNull)`; `Map<String,PeerMode> apiModes()`; `void loadApiModes(Map<String,PeerMode>)`
    - `List<Peer> snapshot()`; `boolean isActive(String id)`; `Optional<String> addressOf(String id)`; `Optional<String> idOf(String address)`; `List<Peer> active()`; `void recompute()`

- [ ] **Step 1: Write failing tests**

`P2pSettingsTest.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P2pSettingsTest {
    @Test
    void overrideBeatsBaseAndNullClearsTheThreshold() {
        P2pSettings s = new P2pSettings(4, 80.0);
        s.patch(Map.of("maxNeighbors", 6));
        assertThat(s.effective().maxNeighbors()).isEqualTo(6);
        assertThat(s.effective().maxLatencyMs()).isEqualTo(80.0);
        Map<String, Object> clear = new HashMap<>();
        clear.put("maxLatencyMs", null);
        s.patch(clear);
        assertThat(s.effective().maxLatencyMs()).isNull();      // explicit null = "not applied"
        s.clearOverrides();
        assertThat(s.effective().maxNeighbors()).isEqualTo(4);
        assertThat(s.effective().maxLatencyMs()).isEqualTo(80.0);
    }

    @Test
    void invalidPatchesAreRejectedAndLeaveStateUntouched() {
        P2pSettings s = new P2pSettings(4, null);
        assertThatThrownBy(() -> s.patch(Map.of("maxNeighbors", -1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.patch(Map.of("maxLatencyMs", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.patch(Map.of("maxLatencyMs", "fast"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.patch(Map.of("bogus", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.overrides()).isEmpty();
        assertThat(s.effective().maxNeighbors()).isEqualTo(4);
    }

    @Test
    void zeroNeighborsIsValid() {
        P2pSettings s = new P2pSettings(null, null);
        s.patch(Map.of("maxNeighbors", 0));
        assertThat(s.effective().maxNeighbors()).isZero();
    }
}
```

`PeerTableTest.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PeerTableTest {
    private final AtomicReference<Settings> settings = new AtomicReference<>(new Settings(null, null));
    private final PeerTable table = new PeerTable(settings::get);
    private static final Coord O = new Coord(0, 0, 0);

    @Test
    void newPeerIsActiveWhenNoRulesApply() {
        table.upsert("a", "10.0.0.1:1");
        assertThat(table.isActive("a")).isTrue();
    }

    @Test
    void thresholdActivatesPeerOnlyAfterAFastMeasurement() {
        settings.set(new Settings(null, 50.0));
        table.upsert("a", "10.0.0.1:1");
        assertThat(table.isActive("a")).isFalse();
        table.recordRtt("a", 20, O);
        assertThat(table.isActive("a")).isTrue();
    }

    @Test
    void apiModeBeatsOperatorModeAndClearingFallsBack() {
        table.upsert("a", "x:1");
        table.setOperatorMode("a", PeerMode.EXCLUDED);
        assertThat(table.isActive("a")).isFalse();
        table.setApiMode("a", PeerMode.FORCE_ACTIVE);
        assertThat(table.isActive("a")).isTrue();
        table.setApiMode("a", null);
        assertThat(table.isActive("a")).isFalse();     // back to the operator's EXCLUDED
    }

    @Test
    void modeSetBeforeThePeerIsDiscoveredApplies() {
        table.setOperatorMode("late", PeerMode.EXCLUDED);
        table.upsert("late", "x:1");
        assertThat(table.isActive("late")).isFalse();
    }

    @Test
    void rejoinWithNewAddressFollowsTheId() {
        table.upsert("a", "old:1");
        table.upsert("a", "new:2");
        assertThat(table.addressOf("a")).contains("new:2");
        assertThat(table.idOf("old:1")).isEmpty();
        assertThat(table.idOf("new:2")).contains("a");
    }

    @Test
    void removedPeerDisappears() {
        table.upsert("a", "x:1");
        table.remove("a");
        assertThat(table.snapshot()).isEmpty();
        assertThat(table.isActive("a")).isFalse();
    }

    @Test
    void restoredHintOrdersButNeverPassesTheThreshold() {
        settings.set(new Settings(null, 50.0));
        table.restore("a", "x:1", 5.0, O);
        assertThat(table.isActive("a")).isFalse();
        assertThat(table.snapshot().getFirst().reason()).isEqualTo("no-measurement");
    }

    @Test
    void recomputeAppliesSettingsChanges() {
        table.upsert("a", "x:1");
        table.recordRtt("a", 30, O);
        table.upsert("b", "y:1");
        table.recordRtt("b", 10, O);
        settings.set(new Settings(1, null));
        table.recompute();
        assertThat(table.isActive("b")).isTrue();
        assertThat(table.isActive("a")).isFalse();
        assertThat(table.active()).extracting(PeerTable.Peer::id).containsExactly("b");
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL (classes missing).

- [ ] **Step 3: Implement**

`P2pSettings.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.HashMap;
import java.util.Map;

/**
 * Layered selection settings: base (application.yml, then file config) overridden at runtime.
 * An override key mapped to null means "not applied" (e.g. maxLatencyMs=null removes the threshold).
 */
public final class P2pSettings {
    private Integer baseMaxNeighbors;
    private Double baseMaxLatencyMs;
    private final Map<String, Object> overrides = new HashMap<>();

    public P2pSettings(Integer maxNeighbors, Double maxLatencyMs) {
        this.baseMaxNeighbors = maxNeighbors;
        this.baseMaxLatencyMs = maxLatencyMs;
    }

    public synchronized void setBase(Integer maxNeighbors, Double maxLatencyMs) {
        this.baseMaxNeighbors = maxNeighbors;
        this.baseMaxLatencyMs = maxLatencyMs;
    }

    public synchronized NeighborSelector.Settings effective() {
        Integer mn = baseMaxNeighbors;
        Double ml = baseMaxLatencyMs;
        if (overrides.containsKey("maxNeighbors")) {
            mn = overrides.get("maxNeighbors") == null ? null : ((Number) overrides.get("maxNeighbors")).intValue();
        }
        if (overrides.containsKey("maxLatencyMs")) {
            ml = overrides.get("maxLatencyMs") == null ? null : ((Number) overrides.get("maxLatencyMs")).doubleValue();
        }
        return new NeighborSelector.Settings(mn, ml);
    }

    public synchronized Map<String, Object> overrides() {
        return new HashMap<>(overrides);
    }

    public synchronized void loadOverrides(Map<String, Object> loaded) {
        overrides.clear();
        if (loaded != null) {
            try {
                patch(loaded);
            } catch (IllegalArgumentException _) {
                overrides.clear();   // a bad saved override must not block startup
            }
        }
    }

    public synchronized void clearOverrides() {
        overrides.clear();
    }

    /** Validates the whole patch first; applies nothing when any entry is invalid. */
    public synchronized void patch(Map<String, Object> patch) {
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            validate(e.getKey(), e.getValue());
        }
        overrides.putAll(patch);
    }

    private static void validate(String key, Object v) {
        switch (key) {
            case "maxNeighbors" -> {
                if (v != null && (!(v instanceof Number n) || n.intValue() < 0 || n.doubleValue() != n.intValue())) {
                    throw new IllegalArgumentException("maxNeighbors must be an integer >= 0 or null");
                }
            }
            case "maxLatencyMs" -> {
                if (v != null && (!(v instanceof Number n) || !(n.doubleValue() > 0))) {
                    throw new IllegalArgumentException("maxLatencyMs must be a number > 0 or null");
                }
            }
            default -> throw new IllegalArgumentException("unknown setting: " + key);
        }
    }
}
```

`PeerTable.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Candidate;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Decision;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Peer state plus the selector's verdicts. All methods are synchronized; peers are few. */
public final class PeerTable {
    private static final int RTT_WINDOW = 9;

    public record Peer(String id, String address, PeerMode mode, Double rttMs, List<Double> coord,
                       boolean active, String reason) {}

    private static final class State {
        String address;
        final RttWindow rtt = new RttWindow(RTT_WINDOW);
        Double hintMs;
        Coord coord = new Coord(0, 0, 0);
        boolean active;
        String reason = "new";
    }

    private final Supplier<NeighborSelector.Settings> settings;
    private final NeighborSelector selector = new NeighborSelector();
    private final Map<String, State> peers = new LinkedHashMap<>();
    private final Map<String, PeerMode> operatorModes = new HashMap<>();
    private final Map<String, PeerMode> apiModes = new HashMap<>();

    public PeerTable(Supplier<NeighborSelector.Settings> settings) {
        this.settings = settings;
    }

    public synchronized void upsert(String id, String address) {
        peers.computeIfAbsent(id, k -> new State()).address = address;
        recompute();
    }

    public synchronized void remove(String id) {
        peers.remove(id);
        recompute();
    }

    public synchronized void recordRtt(String id, double rttMs, Coord coord) {
        State s = peers.get(id);
        if (s == null) return;
        s.rtt.add(rttMs);
        s.coord = coord;
        recompute();
    }

    public synchronized void restore(String id, String address, Double rttHintMs, Coord coord) {
        State s = peers.computeIfAbsent(id, k -> new State());
        s.address = address;
        s.hintMs = rttHintMs;
        s.coord = coord;
        recompute();
    }

    public synchronized void setOperatorMode(String id, PeerMode mode) {
        operatorModes.put(id, mode);
        recompute();
    }

    public synchronized void setApiMode(String id, PeerMode modeOrNull) {
        if (modeOrNull == null) apiModes.remove(id);
        else apiModes.put(id, modeOrNull);
        recompute();
    }

    public synchronized Map<String, PeerMode> apiModes() {
        return new HashMap<>(apiModes);
    }

    public synchronized void loadApiModes(Map<String, PeerMode> modes) {
        apiModes.clear();
        if (modes != null) apiModes.putAll(modes);
        recompute();
    }

    public synchronized List<Peer> snapshot() {
        List<Peer> out = new ArrayList<>();
        peers.forEach((id, s) -> {
            Double rtt = s.rtt.median();
            out.add(new Peer(id, s.address, mode(id), rtt != null ? rtt : s.hintMs, s.coord.toList(), s.active, s.reason));
        });
        out.sort(Comparator.comparing(Peer::id));
        return out;
    }

    public synchronized List<Peer> active() {
        return snapshot().stream().filter(Peer::active).toList();
    }

    public synchronized boolean isActive(String id) {
        State s = peers.get(id);
        return s != null && s.active;
    }

    public synchronized Optional<String> addressOf(String id) {
        return Optional.ofNullable(peers.get(id)).map(s -> s.address);
    }

    public synchronized Optional<String> idOf(String address) {
        return peers.entrySet().stream().filter(e -> address.equals(e.getValue().address))
                .map(Map.Entry::getKey).findFirst();
    }

    public synchronized void recompute() {
        List<Candidate> cands = new ArrayList<>();
        peers.forEach((id, s) -> cands.add(new Candidate(id, mode(id), s.rtt.median(), s.hintMs, s.active)));
        Map<String, Decision> decisions = selector.select(cands, settings.get());
        decisions.forEach((id, d) -> {
            State s = peers.get(id);
            s.active = d.active();
            s.reason = d.reason();
        });
    }

    private PeerMode mode(String id) {
        PeerMode api = apiModes.get(id);
        if (api != null) return api;
        return operatorModes.getOrDefault(id, PeerMode.AUTO);
    }
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*PeerTableTest' --tests '*P2pSettingsTest'` → PASS.

- [ ] **Step 5: Commit** — `git add platform/modules/p2p-discovery && git commit -m "feat(p2p): PeerTable and layered settings"`

---

### Task 6: PeerMessaging and LatencyMonitor

**Files:**
- Create: `.../p2pdiscovery/PeerMessaging.java`, `.../p2pdiscovery/LatencyMonitor.java`
- Test: `.../p2pdiscovery/PeerMessagingTest.java`, `.../p2pdiscovery/LatencyMonitorTest.java`

**Interfaces:**
- Consumes: `PeerTable` (Task 5), `Vivaldi` (Task 3), `PeerCluster.Handler` shape (Task 4).
- Produces:
  - `PeerMessaging.Transport` — a tiny seam so the class is testable without sockets: `interface Wire { Mono<Void> send(String address, String topic, byte[] p); Mono<byte[]> request(String address, String topic, byte[] p, Duration t); void handle(String topic, PeerCluster.Handler h); }`; `PeerCluster` is adapted with `PeerMessaging.wire(PeerCluster)`
  - `PeerMessaging(Wire wire, PeerTable table)`; `Mono<Void> send(String peerId, String topic, byte[] payload)`; `Mono<byte[]> request(String peerId, String topic, byte[] payload, Duration timeout)`; `Mono<Integer> broadcast(String topic, byte[] payload)` (emits number of recipients); `void subscribe(String topic, Receiver r)`; `interface Receiver { Mono<byte[]> onMessage(String senderId, byte[] payload); }`; `static class PeerNotActiveException extends RuntimeException`; reserved prefix `p2p.` → `IllegalArgumentException`
  - `LatencyMonitor(Wire wire, PeerTable table, Vivaldi vivaldi, Duration timeout, java.util.function.LongSupplier nanoClock)`; `Mono<Void> pingAll()` (one round, never errors); `void register()` (installs the `p2p.ping` handler); constant `LatencyMonitor.TOPIC = "p2p.ping"`; payload = 4 big-endian doubles `x,y,z,error`

- [ ] **Step 1: Write failing tests**

`PeerMessagingTest.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerMessaging.PeerNotActiveException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeerMessagingTest {
    /** Records traffic and lets the test inject incoming messages. */
    static final class FakeWire implements PeerMessaging.Wire {
        final List<String> sent = new ArrayList<>();
        final Map<String, PeerCluster.Handler> handlers = new HashMap<>();

        public Mono<Void> send(String a, String t, byte[] p) { sent.add(a + "|" + t); return Mono.empty(); }
        public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { sent.add(a + "|" + t); return Mono.just(new byte[]{1}); }
        public void handle(String t, PeerCluster.Handler h) { handlers.put(t, h); }
    }

    private final FakeWire wire = new FakeWire();
    private final PeerTable table = new PeerTable(() -> new Settings(null, null));
    private final PeerMessaging messaging = new PeerMessaging(wire, table);

    @Test
    void sendGoesToTheAddressOfAnActivePeer() {
        table.upsert("a", "10.0.0.1:1");
        messaging.send("a", "t", new byte[0]).block();
        assertThat(wire.sent).containsExactly("10.0.0.1:1|t");
    }

    @Test
    void sendToInactiveOrUnknownPeerFailsExplicitly() {
        table.upsert("x", "10.0.0.9:1");
        table.setApiMode("x", PeerMode.EXCLUDED);
        assertThatThrownBy(() -> messaging.send("x", "t", new byte[0]).block()).isInstanceOf(PeerNotActiveException.class);
        assertThatThrownBy(() -> messaging.request("nobody", "t", new byte[0], Duration.ofSeconds(1)).block())
                .isInstanceOf(PeerNotActiveException.class);
        assertThat(wire.sent).isEmpty();
    }

    @Test
    void broadcastReachesOnlyActivePeers() {
        table.upsert("a", "a:1");
        table.upsert("b", "b:1");
        table.setApiMode("b", PeerMode.EXCLUDED);
        assertThat(messaging.broadcast("t", new byte[0]).block()).isEqualTo(1);
        assertThat(wire.sent).containsExactly("a:1|t");
    }

    @Test
    void incomingFromActivePeerIsDeliveredWithItsId() {
        table.upsert("a", "a:1");
        List<String> got = new ArrayList<>();
        messaging.subscribe("t", (sender, p) -> { got.add(sender); return Mono.just(new byte[]{9}); });
        byte[] reply = wire.handlers.get("t").onMessage("a:1", new byte[0]).block();
        assertThat(got).containsExactly("a");
        assertThat(reply).containsExactly(9);
    }

    @Test
    void incomingFromInactiveOrUnknownSenderIsDropped() {
        table.upsert("b", "b:1");
        table.setApiMode("b", PeerMode.EXCLUDED);
        List<String> got = new ArrayList<>();
        messaging.subscribe("t", (s, p) -> { got.add(s); return Mono.empty(); });
        wire.handlers.get("t").onMessage("b:1", new byte[0]).block();
        wire.handlers.get("t").onMessage("stranger:1", new byte[0]).block();
        assertThat(got).isEmpty();
    }

    @Test
    void reservedTopicPrefixIsRejected() {
        assertThatThrownBy(() -> messaging.subscribe("p2p.ping", (s, p) -> Mono.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messaging.send("a", "p2p.x", new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
```

`LatencyMonitorTest.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyMonitorTest {
    private final AtomicLong clock = new AtomicLong();
    private final PeerTable table = new PeerTable(() -> new Settings(null, 50.0));
    private final Vivaldi vivaldi = new Vivaldi(1);

    private static byte[] pong() {
        return ByteBuffer.allocate(32).putDouble(10).putDouble(0).putDouble(0).putDouble(0.5).array();
    }

    /** A wire whose request() advances the fake clock by the configured RTT. */
    private PeerMessaging.Wire wire(long rttNanos, boolean fail) {
        return new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) {
                return Mono.defer(() -> {
                    clock.addAndGet(rttNanos);
                    return fail ? Mono.error(new RuntimeException("timeout")) : Mono.just(pong());
                });
            }
            public void handle(String t, PeerCluster.Handler h) {}
        };
    }

    @Test
    void pingRecordsRttAndActivatesAFastPeer() {
        table.upsert("a", "a:1");
        new LatencyMonitor(wire(20_000_000L, false), table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.snapshot().getFirst().rttMs()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(table.isActive("a")).isTrue();
    }

    @Test
    void slowPeerStaysOutUnderTheThreshold() {
        table.upsert("a", "a:1");
        new LatencyMonitor(wire(90_000_000L, false), table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.isActive("a")).isFalse();
    }

    @Test
    void failedPingIsSwallowedAndRecordsNothing() {
        table.upsert("a", "a:1");
        new LatencyMonitor(wire(1, true), table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.snapshot().getFirst().rttMs()).isNull();
    }

    @Test
    void pingHandlerRepliesWithLocalCoordinateAndError() {
        var captured = new PeerCluster.Handler[1];
        PeerMessaging.Wire w = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.empty(); }
            public void handle(String t, PeerCluster.Handler h) { assertThat(t).isEqualTo(LatencyMonitor.TOPIC); captured[0] = h; }
        };
        new LatencyMonitor(w, table, vivaldi, Duration.ofSeconds(1), clock::get).register();
        ByteBuffer reply = ByteBuffer.wrap(captured[0].onMessage("x:1", pong()).block());
        assertThat(reply.remaining()).isEqualTo(32);
        reply.getDouble(); reply.getDouble(); reply.getDouble();
        assertThat(reply.getDouble()).isEqualTo(vivaldi.error());
    }

    @Test
    void malformedPongIsIgnored() {
        table.upsert("a", "a:1");
        PeerMessaging.Wire w = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.just(new byte[3]); }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        new LatencyMonitor(w, table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.snapshot().getFirst().rttMs()).isNull();
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL (classes missing).

- [ ] **Step 3: Implement**

`PeerMessaging.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Send/receive primitives restricted to active neighbors. Application payloads are opaque bytes. */
public final class PeerMessaging {
    static final String RESERVED_PREFIX = "p2p.";

    /** Seam over {@link PeerCluster} so messaging and latency logic are testable without sockets. */
    public interface Wire {
        Mono<Void> send(String address, String topic, byte[] payload);

        Mono<byte[]> request(String address, String topic, byte[] payload, Duration timeout);

        void handle(String topic, PeerCluster.Handler handler);
    }

    @FunctionalInterface
    public interface Receiver {
        Mono<byte[]> onMessage(String senderId, byte[] payload);
    }

    public static final class PeerNotActiveException extends RuntimeException {
        public PeerNotActiveException(String peerId) {
            super("peer is not an active neighbor: " + peerId);
        }
    }

    public static Wire wire(PeerCluster c) {
        return new Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return c.send(a, t, p); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return c.request(a, t, p, d); }
            public void handle(String t, PeerCluster.Handler h) { c.handle(t, h); }
        };
    }

    private final Wire wire;
    private final PeerTable table;

    public PeerMessaging(Wire wire, PeerTable table) {
        this.wire = wire;
        this.table = table;
    }

    public Mono<Void> send(String peerId, String topic, byte[] payload) {
        checkTopic(topic);
        return activeAddress(peerId).flatMap(a -> wire.send(a, topic, payload));
    }

    public Mono<byte[]> request(String peerId, String topic, byte[] payload, Duration timeout) {
        checkTopic(topic);
        return activeAddress(peerId).flatMap(a -> wire.request(a, topic, payload, timeout));
    }

    /** Emits the number of active peers the message was sent to. */
    public Mono<Integer> broadcast(String topic, byte[] payload) {
        checkTopic(topic);
        var targets = table.active();
        return Flux.fromIterable(targets)
                .flatMap(p -> wire.send(p.address(), topic, payload).onErrorResume(e -> Mono.empty()))
                .then(Mono.just(targets.size()));
    }

    public void subscribe(String topic, Receiver receiver) {
        checkTopic(topic);
        wire.handle(topic, (senderAddress, payload) -> {
            String id = table.idOf(senderAddress).orElse(null);
            if (id == null || !table.isActive(id)) {
                return Mono.empty();   // not an active neighbor: drop silently on the receive side
            }
            return receiver.onMessage(id, payload);
        });
    }

    private Mono<String> activeAddress(String peerId) {
        return Mono.defer(() -> table.isActive(peerId)
                ? Mono.justOrEmpty(table.addressOf(peerId))
                : Mono.error(new PeerNotActiveException(peerId)));
    }

    private static void checkTopic(String topic) {
        if (topic == null || topic.isBlank() || topic.startsWith(RESERVED_PREFIX)) {
            throw new IllegalArgumentException("invalid or reserved topic: " + topic);
        }
    }
}
```

`LatencyMonitor.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.LongSupplier;

/** One ping round trip per known peer: yields the RTT and the remote Vivaldi coordinate. */
public final class LatencyMonitor {
    private static final Logger log = LoggerFactory.getLogger(LatencyMonitor.class);
    static final String TOPIC = "p2p.ping";
    private static final int PAYLOAD = 4 * Double.BYTES;

    private final PeerMessaging.Wire wire;
    private final PeerTable table;
    private final Vivaldi vivaldi;
    private final Duration timeout;
    private final LongSupplier nanoClock;

    public LatencyMonitor(PeerMessaging.Wire wire, PeerTable table, Vivaldi vivaldi,
                          Duration timeout, LongSupplier nanoClock) {
        this.wire = wire;
        this.table = table;
        this.vivaldi = vivaldi;
        this.timeout = timeout;
        this.nanoClock = nanoClock;
    }

    /** Installs the responder: any ping is answered with our own coordinate and error. */
    public void register() {
        wire.handle(TOPIC, (sender, payload) -> Mono.fromSupplier(this::encodeLocal));
    }

    /** One round over every known peer (active or not: unmeasured peers must be measurable). Never errors. */
    public Mono<Void> pingAll() {
        return Flux.fromIterable(table.snapshot())
                .flatMap(p -> ping(p.id(), p.address()), 8)
                .then();
    }

    private Mono<Void> ping(String id, String address) {
        return Mono.defer(() -> {
            long start = nanoClock.getAsLong();
            return wire.request(address, TOPIC, encodeLocal(), timeout)
                    .doOnNext(reply -> onPong(id, (nanoClock.getAsLong() - start) / 1e6, reply))
                    .doOnError(e -> log.debug("ping {} failed: {}", id, e.toString()))
                    .onErrorResume(e -> Mono.empty())
                    .then();
        });
    }

    private void onPong(String id, double rttMs, byte[] reply) {
        if (reply.length != PAYLOAD) {
            log.debug("ignoring malformed pong from {} ({} bytes)", id, reply.length);
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(reply);
        Coord remote = new Coord(b.getDouble(), b.getDouble(), b.getDouble());
        double remoteError = b.getDouble();
        vivaldi.update(rttMs, remote, remoteError);
        table.recordRtt(id, rttMs, remote);
    }

    private byte[] encodeLocal() {
        Coord c = vivaldi.coord();
        return ByteBuffer.allocate(PAYLOAD).putDouble(c.x()).putDouble(c.y()).putDouble(c.z())
                .putDouble(vivaldi.error()).array();
    }
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*PeerMessagingTest' --tests '*LatencyMonitorTest'` → PASS.

- [ ] **Step 5: Commit** — `git add platform/modules/p2p-discovery && git commit -m "feat(p2p): neighbor-restricted messaging and latency monitor"`

---

### Task 7: State file (operator config + node state)

**Files:**
- Create: `.../p2pdiscovery/P2pFile.java`, `.../p2pdiscovery/P2pStateFile.java`
- Test: `.../p2pdiscovery/P2pStateFileTest.java`

**Interfaces:**
- Consumes: `PeerMode` (Task 1).
- Produces:
  - `record P2pFile(Config config, State state)` (nested records, below); never-null accessors via normalizing constructors
  - `P2pStateFile(Path path)`; `P2pFile load()` (never throws; missing/empty/corrupt → empty `P2pFile`, logs a warning); `void saveState(P2pFile.State state)` (atomic; re-reads and keeps `config`)
  - Records: `P2pFile.Config(List<String> seeds, Integer maxNeighbors, Double maxLatencyMs, List<PeerEntry> peers)`, `P2pFile.PeerEntry(String id, PeerMode mode)`, `P2pFile.State(String nodeId, List<Double> coord, Double coordError, Map<String,Object> overrides, Map<String,PeerMode> peerModes, List<KnownPeer> peers)`, `P2pFile.KnownPeer(String id, String address, Double rttMs, List<Double> coord)`

- [ ] **Step 1: Write failing tests**

```java
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
    void explicitNullOverrideSurvivesTheRoundTrip() throws Exception {
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
}
```

- [ ] **Step 2: Run to verify failure** → FAIL.

- [ ] **Step 3: Implement**

`P2pFile.java` (separate small file for the records):
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** On-disk shape: {@code config} belongs to the operator, {@code state} to the node. */
public record P2pFile(Config config, State state) {
    public P2pFile {
        if (config == null) config = new Config(null, null, null, null);
        if (state == null) state = new State(null, null, null, null, null, null);
    }

    public record PeerEntry(String id, PeerMode mode) {}

    public record Config(List<String> seeds, Integer maxNeighbors, Double maxLatencyMs, List<PeerEntry> peers) {
        public Config {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
            peers = peers == null ? List.of() : List.copyOf(peers);
        }
    }

    public record KnownPeer(String id, String address, Double rttMs, List<Double> coord) {}

    public record State(String nodeId, List<Double> coord, Double coordError, Map<String, Object> overrides,
                        Map<String, PeerMode> peerModes, List<KnownPeer> peers) {
        public State {
            overrides = overrides == null ? Map.of() : new HashMap<>(overrides);   // HashMap: null values allowed
            peerModes = peerModes == null ? Map.of() : Map.copyOf(peerModes);
            peers = peers == null ? List.of() : List.copyOf(peers);
        }
    }
}
```

`P2pStateFile.java`:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads the YAML file at boot and rewrites only its {@code state} section, atomically. */
public final class P2pStateFile {
    private static final Logger log = LoggerFactory.getLogger(P2pStateFile.class);
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final Path path;

    public P2pStateFile(Path path) {
        this.path = path;
    }

    /** Never throws: a missing, empty or corrupt file yields an empty model (and is left untouched). */
    public P2pFile load() {
        if (!Files.isRegularFile(path)) {
            return new P2pFile(null, null);
        }
        try {
            if (Files.size(path) == 0) {
                return new P2pFile(null, null);
            }
            P2pFile f = YAML.readValue(path.toFile(), P2pFile.class);
            return f == null ? new P2pFile(null, null) : f;
        } catch (IOException | RuntimeException e) {
            log.warn("ignoring unreadable p2p state file {}: {}", path, e.toString());
            return new P2pFile(null, null);
        }
    }

    /** Keeps the operator's {@code config}, replaces {@code state}; write temp file then atomic move. */
    public synchronized void saveState(P2pFile.State state) {
        try {
            Path dir = path.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            P2pFile out = new P2pFile(load().config(), state);
            Path tmp = Files.createTempFile(dir, path.getFileName().toString(), ".tmp");
            try {
                YAML.writeValue(tmp.toFile(), out);
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```
Note: the test imports `P2pFile.*`; `P2pFile` and `P2pStateFile` are both in the main package.

- [ ] **Step 4: Run to verify pass** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*P2pStateFileTest'` → PASS.
  - Jackson 3 specifics to expect: package `tools.jackson.dataformat.yaml`; `JacksonException` is unchecked (hence the `RuntimeException` catch); records bind by constructor. If the explicit-null test fails because Jackson skips null map values on write, set the mapper with `.changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.ALWAYS))` for map content only — keep the change minimal and re-run.

- [ ] **Step 5: Commit** — `git add platform/modules/p2p-discovery && git commit -m "feat(p2p): YAML config+state file with atomic state writes"`

---

### Task 8: Admin controller

**Files:**
- Create: `.../p2pdiscovery/P2pAdminController.java`
- Test: `.../p2pdiscovery/P2pAdminControllerTest.java`

**Interfaces:**
- Consumes: `PeerTable`, `P2pSettings`, `PeerMode`.
- Produces: `P2pAdminController(PeerTable table, P2pSettings settings, Runnable persistNow)` — `persistNow` is invoked after every successful mutation. Endpoints under `/v1/admin/p2p`:
  - `GET /peers` → `200 [PeerView]`, `PeerView(String id, String address, String mode, Double rttMs, List<Double> coord, boolean active, String reason)`
  - `PUT /peers/{id}` body `{"mode": "AUTO|FORCE_ACTIVE|EXCLUDED"}` → `200`; invalid/missing mode → `400`; `AUTO` clears the API mode
  - `GET /config` → `{"effective": {...}, "overrides": {...}}`
  - `PATCH /config` body `{"maxNeighbors": n|null, "maxLatencyMs": x|null}` → `200` effective; invalid → `400 {"error": msg}`
  - `DELETE /overrides` → `200` (clears settings overrides AND API peer modes)

- [ ] **Step 1: Write failing tests** (plain-object style, like `AdminRuntimeConfigControllerTest`)

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class P2pAdminControllerTest {
    private final P2pSettings settings = new P2pSettings(null, null);
    private final PeerTable table = new PeerTable(settings::effective);
    private final AtomicInteger persisted = new AtomicInteger();
    private final P2pAdminController ctl = new P2pAdminController(table, settings, persisted::incrementAndGet);

    @Test
    void peersListShowsStateAndReason() {
        table.upsert("a", "a:1");
        var body = ctl.peers().getBody();
        assertThat(body).hasSize(1);
        assertThat(body.getFirst().active()).isTrue();
        assertThat(body.getFirst().mode()).isEqualTo("AUTO");
    }

    @Test
    void putModeExcludesAndAutoRestores() {
        table.upsert("a", "a:1");
        assertThat(ctl.putPeer("a", Map.of("mode", "EXCLUDED")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(table.isActive("a")).isFalse();
        ctl.putPeer("a", Map.of("mode", "AUTO"));
        assertThat(table.isActive("a")).isTrue();
        assertThat(persisted.get()).isEqualTo(2);
    }

    @Test
    void putModeAcceptsAPeerNotDiscoveredYet() {
        assertThat(ctl.putPeer("ghost", Map.of("mode", "EXCLUDED")).getStatusCode()).isEqualTo(HttpStatus.OK);
        table.upsert("ghost", "g:1");
        assertThat(table.isActive("ghost")).isFalse();
    }

    @Test
    void putBadModeIs400AndChangesNothing() {
        table.upsert("a", "a:1");
        assertThat(ctl.putPeer("a", Map.of("mode", "BANANA")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ctl.putPeer("a", Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(persisted.get()).isZero();
    }

    @Test
    void patchConfigAppliesAndRecomputes() {
        table.upsert("a", "a:1"); table.recordRtt("a", 30, new Vivaldi.Coord(0, 0, 0));
        table.upsert("b", "b:1"); table.recordRtt("b", 10, new Vivaldi.Coord(0, 0, 0));
        ResponseEntity<?> r = ctl.patchConfig(Map.of("maxNeighbors", 1));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(table.isActive("b")).isTrue();
        assertThat(table.isActive("a")).isFalse();
    }

    @Test
    void patchConfigRejectsInvalidValuesWithoutPersisting() {
        for (Map<String, Object> bad : java.util.List.<Map<String, Object>>of(
                Map.of("maxNeighbors", -1), Map.of("maxLatencyMs", 0), Map.of("maxLatencyMs", "x"), Map.of("nope", 1))) {
            assertThat(ctl.patchConfig(bad).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(persisted.get()).isZero();
    }

    @Test
    void patchNullRemovesTheThreshold() {
        settings.setBase(null, 50.0);
        Map<String, Object> body = new HashMap<>();
        body.put("maxLatencyMs", null);
        ctl.patchConfig(body);
        assertThat(settings.effective().maxLatencyMs()).isNull();
    }

    @Test
    void deleteOverridesClearsSettingsAndApiModes() {
        table.upsert("a", "a:1");
        ctl.putPeer("a", Map.of("mode", "EXCLUDED"));
        ctl.patchConfig(Map.of("maxNeighbors", 0));
        ctl.deleteOverrides();
        assertThat(settings.overrides()).isEmpty();
        assertThat(table.apiModes()).isEmpty();
        assertThat(table.isActive("a")).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL.

- [ ] **Step 3: Implement**

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Registered as a bean only when nanofaas.p2p.enabled and nanofaas.p2p.admin.enabled are true. */
@RestController
@RequestMapping("/v1/admin/p2p")
public class P2pAdminController {
    private static final String ERROR_KEY = "error";

    private final PeerTable table;
    private final P2pSettings settings;
    private final Runnable persistNow;

    public P2pAdminController(PeerTable table, P2pSettings settings, Runnable persistNow) {
        this.table = table;
        this.settings = settings;
        this.persistNow = persistNow;
    }

    public record PeerView(String id, String address, String mode, Double rttMs, List<Double> coord,
                           boolean active, String reason) {}

    @GetMapping("/peers")
    public ResponseEntity<List<PeerView>> peers() {
        return ResponseEntity.ok(table.snapshot().stream()
                .map(p -> new PeerView(p.id(), p.address(), p.mode().name(), p.rttMs(), p.coord(), p.active(), p.reason()))
                .toList());
    }

    @PutMapping("/peers/{id}")
    public ResponseEntity<Object> putPeer(@PathVariable String id, @RequestBody Map<String, Object> body) {
        PeerMode mode;
        try {
            mode = PeerMode.valueOf(String.valueOf(body.get("mode")));
        } catch (IllegalArgumentException _) {
            return ResponseEntity.badRequest().body(Map.of(ERROR_KEY, "mode must be one of AUTO, FORCE_ACTIVE, EXCLUDED"));
        }
        table.setApiMode(id, mode == PeerMode.AUTO ? null : mode);
        persistNow.run();
        return ResponseEntity.ok(Map.of("id", id, "mode", mode.name()));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(view());
    }

    @PatchMapping("/config")
    public ResponseEntity<Object> patchConfig(@RequestBody Map<String, Object> body) {
        try {
            settings.patch(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(ERROR_KEY, e.getMessage()));
        }
        table.recompute();
        persistNow.run();
        return ResponseEntity.ok(view());
    }

    @DeleteMapping("/overrides")
    public ResponseEntity<Object> deleteOverrides() {
        settings.clearOverrides();
        table.loadApiModes(Map.of());
        persistNow.run();
        return ResponseEntity.ok(view());
    }

    private Map<String, Object> view() {
        NeighborSelector.Settings e = settings.effective();
        Map<String, Object> effective = new java.util.HashMap<>();
        effective.put("maxNeighbors", e.maxNeighbors());
        effective.put("maxLatencyMs", e.maxLatencyMs());
        return Map.of("effective", effective, "overrides", settings.overrides());
    }
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*P2pAdminControllerTest'` → PASS.

- [ ] **Step 5: Commit** — `git add platform/modules/p2p-discovery && git commit -m "feat(p2p): admin API for peers and runtime config"`

---

### Task 9: P2pService (lifecycle, persistence, metrics) and wiring

**Files:**
- Create: `.../p2pdiscovery/P2pService.java`
- Modify: `.../p2pdiscovery/P2pConfiguration.java`
- Test: `.../p2pdiscovery/P2pServiceTest.java`, `.../p2pdiscovery/P2pModuleIntegrationTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `P2pService implements SmartLifecycle`: `P2pService(P2pProperties props, PeerTable table, P2pSettings settings, MeterRegistry meters)`; `start()`/`stop()`; `PeerMessaging messaging()` (throws `IllegalStateException` when disabled); `void persistNow()`; `boolean isRunning()`
  - Beans in `P2pConfiguration`: `P2pSettings p2pSettings(P2pProperties)`, `PeerTable peerTable(P2pSettings)`, `P2pService p2pService(...)`, and `P2pAdminController` conditional on `nanofaas.p2p.enabled=true` **and** `nanofaas.p2p.admin.enabled=true`
  - Metrics (only when enabled): `p2p_peers` gauge, `p2p_peers_active` gauge, `p2p_peer_rtt_ms{peer}` gauge per known peer
  - Startup sequence (when enabled): load file → node id (`props.nodeId` ?? `state.nodeId` ?? random UUID) → settings base = props overridden by file config where the file sets a value (`config.maxNeighbors`/`maxLatencyMs` non-null win over yml) → `settings.loadOverrides(state.overrides)` → operator modes into table, `table.loadApiModes(state.peerModes)` → restore Vivaldi, keep known peers as *hints* (they enter the table only when the cluster reports them ADDED) → seeds = props.seeds + file config seeds + known peer addresses (distinct) → `PeerCluster.start()` → subscribe events (ADDED→`upsert`, REMOVED→`remove`), seed table from `cluster.members()` → `LatencyMonitor.register()`, schedule `pingAll()` every `pingInterval` → schedule a persist every 5 s when dirty, and persist on `stop()`
  - Duplicate `nodeId` detection: if an ADDED event carries our own id with another address, log `ERROR`.

- [ ] **Step 1: Write failing tests**

`P2pServiceTest.java` — disabled is inert; enabled boots, restores, persists:
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class P2pServiceTest {
    @TempDir Path dir;

    private static P2pProperties props(boolean enabled, String stateFile, String nodeId, java.util.List<String> seeds) {
        return new P2pProperties(enabled, nodeId, 0, "127.0.0.1", seeds, null, null,
                Duration.ofMillis(200), Duration.ofSeconds(1), stateFile, null);
    }

    private static P2pService service(P2pProperties p) {
        P2pSettings s = new P2pSettings(p.maxNeighbors(), p.maxLatencyMs());
        return new P2pService(p, new PeerTable(s::effective), s, new SimpleMeterRegistry());
    }

    @Test
    void disabledServiceDoesNothing() {
        P2pService svc = service(props(false, null, null, java.util.List.of()));
        svc.start();
        assertThat(svc.isRunning()).isFalse();
        assertThatThrownBy(svc::messaging).isInstanceOf(IllegalStateException.class);
        svc.stop();
    }

    @Test
    void generatedNodeIdIsPersistedAndReusedAfterRestart() {
        String file = dir.resolve("p2p.yaml").toString();
        P2pService first = service(props(true, file, null, java.util.List.of()));
        first.start();
        first.persistNow();
        first.stop();
        String id = new P2pStateFile(Path.of(file)).load().state().nodeId();
        assertThat(id).isNotBlank();

        P2pService second = service(props(true, file, null, java.util.List.of()));
        second.start();
        second.persistNow();
        second.stop();
        assertThat(new P2pStateFile(Path.of(file)).load().state().nodeId()).isEqualTo(id);
    }

    @Test
    void corruptStateFileDoesNotStopBoot() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        Files.writeString(f, "{{{{ not yaml");
        P2pService svc = service(props(true, f.toString(), "n1", java.util.List.of()));
        svc.start();
        assertThat(svc.isRunning()).isTrue();
        svc.stop();
    }

    @Test
    void fileConfigBeatsApplicationYmlAndOverridesBeatFileConfig() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        Files.writeString(f, """
                config:
                  maxNeighbors: 3
                state:
                  overrides: {maxNeighbors: 7}
                """);
        P2pProperties p = new P2pProperties(true, "n1", 0, "127.0.0.1", null, 1, null,
                Duration.ofMillis(200), Duration.ofSeconds(1), f.toString(), null);
        P2pSettings s = new P2pSettings(p.maxNeighbors(), p.maxLatencyMs());
        P2pService svc = new P2pService(p, new PeerTable(s::effective), s, new SimpleMeterRegistry());
        svc.start();
        assertThat(s.effective().maxNeighbors()).isEqualTo(7);   // state override > file config > yml (1)
        s.clearOverrides();
        assertThat(s.effective().maxNeighbors()).isEqualTo(3);   // file config > yml
        svc.stop();
    }

    @Test
    void savedPeerThatNeverComesBackIsNotInTheTableButSurvivesInTheFile() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        Files.writeString(f, """
                state:
                  nodeId: n1
                  peers:
                    - {id: ghost, address: "127.0.0.1:1", rttMs: 5.0}
                """);
        P2pService svc = service(props(true, f.toString(), "n1", java.util.List.of()));
        svc.start();
        assertThat(svc.tableForTest().snapshot()).isEmpty();        // unconfirmed hint is not a peer
        svc.persistNow();
        svc.stop();
        assertThat(new P2pStateFile(f).load().state().peers()).extracting(P2pFile.KnownPeer::id).contains("ghost");
    }

    @Test
    void secondNodeJoinsViaSeedAndKnownPeersArePersistedForRestart() {
        P2pService a = service(props(true, null, "a", java.util.List.of()));
        a.start();
        String seed = a.address();
        String file = dir.resolve("b.yaml").toString();
        P2pService b = service(props(true, file, "b", java.util.List.of(seed)));
        b.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> b.tableForTest().snapshot().size() == 1);
        b.persistNow();
        assertThat(new P2pStateFile(Path.of(file)).load().state().peers())
                .extracting(P2pFile.KnownPeer::id).containsExactly("a");
        b.stop();
        a.stop();
    }
}
```
(Expose `String address()` and a package-private `PeerTable tableForTest()` on `P2pService`.)

`P2pModuleIntegrationTest.java` — Spring wiring only (module loaded, inert by default, admin bean conditional):
```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class P2pModuleIntegrationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(P2pConfiguration.class);

    @Test
    void inertByDefaultAndNoAdminController() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(P2pService.class);
            assertThat(ctx.getBean(P2pService.class).isRunning()).isFalse();
            assertThat(ctx).doesNotHaveBean(P2pAdminController.class);
        });
    }

    @Test
    void adminControllerOnlyWhenBothSwitchesAreOn() {
        runner.withPropertyValues("nanofaas.p2p.enabled=true", "nanofaas.p2p.admin.enabled=true",
                        "nanofaas.p2p.node-id=n1", "nanofaas.p2p.port=0")
                .run(ctx -> assertThat(ctx).hasSingleBean(P2pAdminController.class));
        runner.withPropertyValues("nanofaas.p2p.admin.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(P2pAdminController.class));
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL.

- [ ] **Step 3: Implement `P2pService.java`**

Restored peers are kept as *hints* and enter the table only when the cluster confirms them (ADDED), so a peer that died while we were down never appears active.

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pFile.KnownPeer;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pFile.PeerEntry;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wires cluster, table, monitor and state file. A no-op unless nanofaas.p2p.enabled=true. */
public class P2pService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(P2pService.class);
    private static final Duration PERSIST_EVERY = Duration.ofSeconds(5);

    private final P2pProperties props;
    private final PeerTable table;
    private final P2pSettings settings;
    private final MeterRegistry meters;
    private final Vivaldi vivaldi = new Vivaldi(System.nanoTime());
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Set<String> peerGauges = ConcurrentHashMap.newKeySet();
    private final Map<String, KnownPeer> hints = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile PeerCluster cluster;
    private volatile PeerMessaging messaging;
    private volatile P2pStateFile stateFile;
    private volatile String nodeId;
    private Disposable pings;
    private Disposable events;
    private Disposable persister;

    public P2pService(P2pProperties props, PeerTable table, P2pSettings settings, MeterRegistry meters) {
        this.props = props;
        this.table = table;
        this.settings = settings;
        this.meters = meters;
    }

    @Override
    public void start() {
        if (!props.enabled() || running) {
            return;
        }
        stateFile = props.stateFile() == null || props.stateFile().isBlank()
                ? null : new P2pStateFile(Path.of(props.stateFile()));
        P2pFile file = stateFile == null ? new P2pFile(null, null) : stateFile.load();

        nodeId = props.nodeId() != null ? props.nodeId()
                : file.state().nodeId() != null ? file.state().nodeId() : UUID.randomUUID().toString();

        // precedence: state overrides > file config > application.yml
        var fc = file.config();
        settings.setBase(fc.maxNeighbors() != null ? fc.maxNeighbors() : props.maxNeighbors(),
                fc.maxLatencyMs() != null ? fc.maxLatencyMs() : props.maxLatencyMs());
        settings.loadOverrides(file.state().overrides());
        for (PeerEntry e : fc.peers()) {
            table.setOperatorMode(e.id(), e.mode() == null ? PeerMode.AUTO : e.mode());
        }
        table.loadApiModes(file.state().peerModes());

        vivaldi.restore(Coord.fromList(file.state().coord()),
                file.state().coordError() == null ? 1.0 : file.state().coordError());
        Set<String> seeds = new LinkedHashSet<>(props.seeds());
        seeds.addAll(fc.seeds());
        for (KnownPeer k : file.state().peers()) {
            hints.put(k.id(), k);
            seeds.add(k.address());
        }

        cluster = new PeerCluster(nodeId, props.port(), props.externalHost(), List.copyOf(seeds));
        cluster.start().block(Duration.ofSeconds(15));
        registerAggregateMetrics();
        events = cluster.events().subscribe(this::onMember);
        cluster.members().forEach(m -> admit(m.id(), m.address()));

        PeerMessaging.Wire wire = PeerMessaging.wire(cluster);
        messaging = new PeerMessaging(wire, table);
        LatencyMonitor monitor = new LatencyMonitor(wire, table, vivaldi, props.pingTimeout(), System::nanoTime);
        monitor.register();
        pings = Flux.interval(props.pingInterval())
                .concatMap(i -> monitor.pingAll()
                        .doOnTerminate(() -> dirty.set(true))
                        .onErrorResume(e -> Mono.empty()))
                .subscribe();
        persister = Flux.interval(PERSIST_EVERY).subscribe(i -> {
            if (dirty.compareAndSet(true, false)) {
                persistNow();
            }
        });
        running = true;
        log.info("p2p-discovery started: node {} at {}", nodeId, cluster.address());
    }

    private void onMember(PeerCluster.MemberEvent e) {
        if (e.type() == PeerCluster.Type.ADDED) {
            admit(e.id(), e.address());
        } else {
            table.remove(e.id());
        }
        dirty.set(true);
    }

    /** A cluster-confirmed peer enters the table, seeded with its saved hint (ordering only, never the threshold). */
    private void admit(String id, String address) {
        if (id.equals(nodeId)) {
            log.error("another node advertises our own id {} at {}: node ids must be unique", nodeId, address);
            return;
        }
        table.upsert(id, address);
        KnownPeer hint = hints.remove(id);
        if (hint != null) {
            table.restore(id, address, hint.rttMs(), Coord.fromList(hint.coord()));
        }
        if (peerGauges.add(id)) {
            Gauge.builder("p2p_peer_rtt_ms", table, t -> t.snapshot().stream()
                            .filter(p -> p.id().equals(id)).findFirst()
                            .map(p -> p.rttMs() == null ? Double.NaN : p.rttMs()).orElse(Double.NaN))
                    .tag("peer", id).register(meters);
        }
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (Disposable d : new Disposable[]{pings, events, persister}) {
            if (d != null) {
                d.dispose();
            }
        }
        persistNow();
        cluster.close();
    }

    public void persistNow() {
        if (stateFile == null || nodeId == null) {
            return;
        }
        try {
            Map<String, KnownPeer> known = new HashMap<>(hints);   // not yet confirmed: keep them for the next restart
            table.snapshot().forEach(p -> known.put(p.id(), new KnownPeer(p.id(), p.address(), p.rttMs(), p.coord())));
            stateFile.saveState(new P2pFile.State(nodeId, vivaldi.coord().toList(), vivaldi.error(),
                    settings.overrides(), table.apiModes(), List.copyOf(known.values())));
        } catch (RuntimeException e) {
            log.warn("could not persist p2p state: {}", e.toString());
        }
    }

    public PeerMessaging messaging() {
        if (messaging == null) {
            throw new IllegalStateException("p2p-discovery is not running");
        }
        return messaging;
    }

    public String address() {
        return cluster.address();
    }

    PeerTable tableForTest() {
        return table;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void registerAggregateMetrics() {
        Gauge.builder("p2p_peers", table, t -> t.snapshot().size()).register(meters);
        Gauge.builder("p2p_peers_active", table, t -> t.active().size()).register(meters);
    }
}
```

- [ ] **Step 4: Implement `P2pConfiguration.java`**

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(P2pProperties.class)
public class P2pConfiguration {

    @Bean
    P2pSettings p2pSettings(P2pProperties props) {
        return new P2pSettings(props.maxNeighbors(), props.maxLatencyMs());
    }

    @Bean
    PeerTable peerTable(P2pSettings settings) {
        return new PeerTable(settings::effective);
    }

    @Bean
    P2pService p2pService(P2pProperties props, PeerTable table, P2pSettings settings,
                          ObjectProvider<MeterRegistry> meters) {
        return new P2pService(props, table, settings, meters.getObject());
    }

    @Bean
    @ConditionalOnProperty(name = {"nanofaas.p2p.enabled", "nanofaas.p2p.admin.enabled"}, havingValue = "true")
    P2pAdminController p2pAdminController(PeerTable table, P2pSettings settings, P2pService service) {
        return new P2pAdminController(table, settings, service::persistNow);
    }
}
```

- [ ] **Step 5: Run to verify pass** — `./gradlew :control-plane-modules:p2p-discovery:test` → PASS (whole module). `P2pServiceTest` real clusters take a few seconds each.

- [ ] **Step 6: Boot check in the real control plane** — start `./gradlew :control-plane:bootRun --args='--nanofaas.p2p.enabled=true --nanofaas.p2p.admin.enabled=true --nanofaas.p2p.node-id=n1 --nanofaas.p2p.state-file=/tmp/p2p-n1.yaml'` in the background; `curl -s localhost:8080/v1/admin/p2p/peers` → `[]`; `curl -s -X PATCH localhost:8080/v1/admin/p2p/config -H 'content-type: application/json' -d '{"maxNeighbors":2}'` → 200 with the effective view; stop it; the `state` section in `/tmp/p2p-n1.yaml` must contain the override and `nodeId: n1`.

- [ ] **Step 7: Commit** — `git add platform/modules/p2p-discovery && git commit -m "feat(p2p): service lifecycle, state persistence, metrics and wiring"`

---

### Task 10: Multi-node integration test and native image

**Files:**
- Test: `.../p2pdiscovery/P2pThreeNodeIntegrationTest.java`
- Create (if needed): `platform/modules/p2p-discovery/src/main/resources/META-INF/native-image/it.unimib.datai.nanofaas/p2p-discovery/reachability-metadata.json`

**Interfaces:** consumes `P2pService`, `PeerMessaging`, `PeerTable`.

- [ ] **Step 1: Write the failing integration test** (3 services in one JVM, real cluster, loopback)

```java
package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class P2pThreeNodeIntegrationTest {
    private final List<P2pService> services = new ArrayList<>();

    @AfterEach
    void stop() {
        services.forEach(P2pService::stop);
    }

    private P2pService node(String id, List<String> seeds, Double maxLatencyMs) {
        P2pProperties p = new P2pProperties(true, id, 0, "127.0.0.1", seeds, null, maxLatencyMs,
                Duration.ofMillis(200), Duration.ofSeconds(1), null, null);
        P2pSettings s = new P2pSettings(p.maxNeighbors(), p.maxLatencyMs());
        P2pService svc = new P2pService(p, new PeerTable(s::effective), s, new SimpleMeterRegistry());
        svc.start();
        services.add(svc);
        return svc;
    }

    @Test
    void discoveryRttAndMessagingWorkEndToEnd() {
        P2pService a = node("a", List.of(), null);
        P2pService b = node("b", List.of(a.address()), null);
        P2pService c = node("c", List.of(a.address()), null);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(a.tableForTest().active()).extracting(PeerTable.Peer::id).containsExactlyInAnyOrder("b", "c");
            assertThat(a.tableForTest().snapshot()).allSatisfy(p -> assertThat(p.rttMs()).isNotNull());
        });

        List<String> got = new CopyOnWriteArrayList<>();
        b.messaging().subscribe("hello", (sender, payload) -> {
            got.add(sender + ":" + new String(payload, StandardCharsets.UTF_8));
            return Mono.just("ack".getBytes(StandardCharsets.UTF_8));
        });
        byte[] reply = a.messaging().request("b", "hello", "hi".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3))
                .block(Duration.ofSeconds(5));
        assertThat(new String(reply, StandardCharsets.UTF_8)).isEqualTo("ack");
        assertThat(got).containsExactly("a:hi");
        assertThat(a.messaging().broadcast("hello", new byte[0]).block()).isEqualTo(2);
    }

    @Test
    void excludingAPeerStopsTrafficBothWays() {
        P2pService a = node("a", List.of(), null);
        P2pService b = node("b", List.of(a.address()), null);
        await().atMost(Duration.ofSeconds(15)).until(() -> a.tableForTest().isActive("b"));
        b.messaging().subscribe("t", (s, p) -> Mono.just(new byte[]{1}));

        a.tableForTest().setApiMode("b", PeerMode.EXCLUDED);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> a.messaging().send("b", "t", new byte[0]).block())
                .isInstanceOf(PeerMessaging.PeerNotActiveException.class);
    }

    @Test
    void impossibleThresholdKeepsEveryoneInactive() {
        P2pService a = node("a", List.of(), 0.0001);   // sub-microsecond: no real RTT can pass
        node("b", List.of(a.address()), null);
        await().atMost(Duration.ofSeconds(15)).until(() -> !a.tableForTest().snapshot().isEmpty());
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(a.tableForTest().active()).isEmpty());
    }
}
```

- [ ] **Step 2: Run** — `./gradlew :control-plane-modules:p2p-discovery:test --tests '*ThreeNode*'` → should PASS if Tasks 1–9 are right; any failure is a real defect to fix in the owning class (add a unit test there first).

- [ ] **Step 3: Native image check** — this is the spike's third question applied to the real module. With every module on the classpath (default `all`) build the native control plane the way the project does:
  `./scripts/native-java-image.sh control-plane` (or `./gradlew :control-plane:nativeCompile`; see `CLAUDE.md`), then run it once with `--nanofaas.p2p.enabled=true --nanofaas.p2p.node-id=n1` and a second instance with `--nanofaas.p2p.seeds=<first address>`.
  Expected on first attempt: runtime errors for missing reflection/serialization (scalecube uses JDK serialization for `Message`/`Member` and Netty reflection). Generate the metadata from the integration test with the agent, e.g.
  `java -agentlib:native-image-agent=config-merge-dir=platform/modules/p2p-discovery/src/main/resources/META-INF/native-image/it.unimib.datai.nanofaas/p2p-discovery -cp <test runtime classpath> org.junit.platform.console.ConsoleLauncher --select-class it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pThreeNodeIntegrationTest`,
  then **trim** the output to the `io.scalecube`, `java.io.Serializable`-related and `it.unimib.datai.nanofaas.modules.p2pdiscovery` entries (the spike produced 14 serialization entries; the agent also records unrelated test/JUnit classes — remove them) and rebuild. Repeat until the two native instances discover each other and `GET /v1/admin/p2p/peers` shows the peer with an RTT.
  Also confirm the control plane **still starts natively with p2p disabled** and note the binary size delta versus `main` in the commit message.

- [ ] **Step 4: Commit** — `git add platform/modules/p2p-discovery && git commit -m "test(p2p): three-node integration and native reachability metadata"`

---

### Task 11: Docs, OpenAPI, spec amendments, full verification

**Files:**
- Create: `platform/modules/p2p-discovery/README.md`
- Modify: `openapi.yaml` (add the five `/v1/admin/p2p/...` paths, same style as `/v1/admin/runtime-config`)
- Modify: `docs/superpowers/specs/2026-10-01-p2p-discovery-design.md` (apply the two deviations listed at the top of this plan; add "exposing `PeerMessaging` to other modules needs an interface in `:common` — out of scope for v1")
- Modify (do not stage): `CLAUDE.md` — add a `p2p-discovery` bullet to the optional modules list and `nanofaas.p2p.*` to Key Configuration. The user has uncommitted edits in this file: edit it, leave it unstaged, and say so in the final message.

- [ ] **Step 1: Write `README.md`** in the style of `platform/modules/offload/README.md`: purpose, the YAML file example from the spec (`config` / `state`), the property table (`nanofaas.p2p.*`), precedence (`state` overrides > file `config` > `application.yml`), the REST API with `curl` examples, the limits (full membership, no NAT traversal, no authentication/encryption, comments in the file are not preserved when the node rewrites it, node ids must be unique and stable), and the metrics names.

- [ ] **Step 2: Align `openapi.yaml`** — add paths `GET /v1/admin/p2p/peers`, `PUT /v1/admin/p2p/peers/{id}`, `GET|PATCH /v1/admin/p2p/config`, `DELETE /v1/admin/p2p/overrides` with request/response schemas matching `P2pAdminController` (`PeerView`, the config view, the 400 error body). `openapi.yaml` is hand-maintained and drifts silently (`IssueCoverageTest` only checks it exists), so check each schema against the controller by hand.

- [ ] **Step 3: Full verification**
  - `./gradlew build` → PASS
  - `./gradlew test --no-parallel` → PASS (module is on the default classpath; control-plane tests must be unaffected)
  - `./scripts/sonar.sh --only java` → 0 findings (project baseline is 0, so any finding is new)
  - Python suite is unaffected unless `scripts/*.sh` changed; if you touched any script, also run `cd experiments && python -m pytest tests/ -v`

- [ ] **Step 4: Commit** — `git add platform/modules/p2p-discovery openapi.yaml docs/superpowers && git commit -m "docs(p2p): module README, OpenAPI paths and spec amendments"`

---

## Self-review notes

- **Spec coverage:** discovery (T4, T9), RTT + Vivaldi (T3, T6), max neighbors / threshold / modes (T2, T5), threshold optional (T2 `noThresholdNoMaxMeansEveryoneActiveEvenUnmeasured`, T5, T8 null-clears), messaging primitives (T6), admin API (T8), config + state file with precedence (T5, T7, T9), fast restart from saved peers (T7, T9), metrics (T9), native (T10), docs/OpenAPI (T11). Fuori scope items are not planned.
- **Type consistency checked:** `NeighborSelector.Settings`/`Candidate`/`Decision` (T2) ↔ `PeerTable` (T5) ↔ `P2pSettings.effective()` (T5); `PeerMessaging.Wire` (T6) ↔ `PeerMessaging.wire(PeerCluster)` and `PeerCluster.Handler` (T4); `P2pFile.*` (T7) ↔ `P2pService` (T9); `P2pAdminController(PeerTable, P2pSettings, Runnable)` (T8) ↔ configuration bean (T9).
