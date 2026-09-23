package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard on the gate. The six classes that carry the HTTP switch contract are enabled by
 * {@code @EnabledIfSystemProperty("nanofaas.selectedControlPlaneModules", …)}, and a JUnit
 * condition that stops matching disables a class <em>silently</em>: the suite reports zero tests
 * for it and stays green. Renaming that property on either side — the control-plane test task
 * that publishes it, or the annotations that read it — would therefore delete the switch's whole
 * HTTP contract without failing anything, which is the shape of failure this campaign has had to
 * close twice in other places.
 *
 * <p>Both directions are checked here rather than trusted:
 *
 * <ul>
 *   <li>the property is absent — the build stopped publishing it;</li>
 *   <li>a contract class is no longer gated on <em>this</em> name — the annotations moved to a name
 *       the build does not set, and the class silently skips.</li>
 * </ul>
 *
 * <p>Two honest limits. The property's <em>value</em> is deliberately not asserted: this class runs
 * under every profile CI selects, and the contract classes are supposed to skip under the profiles
 * that exclude a queue module — it is the name, not the selection, that must not drift. And it does
 * not count executions: it pins the gate, not that a given run opened it. Where the run's selection
 * is the point, the classes themselves assert it (see {@code SchedulerStrategyConfigurationTest}).
 * Referencing the contract classes by {@code .class} also makes a rename of one of them a compile
 * error here rather than a quietly smaller suite.
 */
class SchedulerSwitchContractGateTest {

    private static final String MODULE_SELECTION_PROPERTY = "nanofaas.selectedControlPlaneModules";

    /** Every class whose contract is "the admin API switches the engine, and nothing else moves". */
    private static final List<Class<?>> GATED_CONTRACT_CLASSES = List.of(
            AdmissionStrategyIndependenceApiTest.class,
            AsyncCapabilityStrategyIndependenceApiTest.class,
            SchedulerStrategyConfigurationTest.class,
            SchedulerSwitchHttpTest.class,
            LegacySyncProfileSchedulerSwitchHttpTest.class,
            SchedulerSwitchInvocationEquivalenceTest.class);

    @Test
    void theModuleSelectionPropertyIsStillPublishedToTheTestsThatGateOnIt() {
        assertThat(System.getProperty(MODULE_SELECTION_PROPERTY))
                .as("the control-plane test task must keep publishing %s; if it stops, every "
                        + "class above reports zero tests instead of failing",
                        MODULE_SELECTION_PROPERTY)
                .isNotNull();
    }

    @Test
    void everySwitchContractClassIsStillGatedOnThatSamePropertyName() {
        for (Class<?> contractClass : GATED_CONTRACT_CLASSES) {
            assertThat(Arrays.stream(contractClass.getAnnotationsByType(EnabledIfSystemProperty.class))
                    .map(EnabledIfSystemProperty::named))
                    .as("%s must stay gated on %s: a gate on any other name silently disables the "
                            + "class instead of failing it",
                            contractClass.getSimpleName(), MODULE_SELECTION_PROPERTY)
                    .contains(MODULE_SELECTION_PROPERTY);
        }
    }
}
