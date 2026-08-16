package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyBudgetAllocatorTest {

    private final ConcurrencyBudgetAllocator allocator = new ConcurrencyBudgetAllocator();

    private static ConcurrencyDemand demand(String name, int desired, double weight) {
        return new ConcurrencyDemand(name, desired, 1, weight);
    }

    @Test
    void everyone_gets_what_they_asked_for_when_the_budget_covers_it() {
        Map<String, Integer> granted = allocator.allocate(
                List.of(demand("a", 4, 1), demand("b", 6, 1)), 32);

        assertThat(granted).containsEntry("a", 4).containsEntry("b", 6);
    }

    @Test
    void the_budget_is_never_exceeded() {
        // The property that makes this mode different: contention is prevented by construction
        // rather than reacted to after the fact.
        Map<String, Integer> granted = allocator.allocate(
                List.of(demand("a", 40, 1), demand("b", 40, 1), demand("c", 40, 1)), 24);

        assertThat(granted.values().stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(24);
    }

    @Test
    void a_modest_function_is_not_cut_to_pay_for_a_greedy_one() {
        // Proportional scaling would take from the small function too. Max-min does not: it is
        // already below its share, so it has nothing to give.
        Map<String, Integer> granted = allocator.allocate(
                List.of(demand("small", 2, 1), demand("large", 100, 1)), 20);

        assertThat(granted).containsEntry("small", 2);
        assertThat(granted.get("large")).isGreaterThan(15);
    }

    @Test
    void what_a_modest_function_leaves_is_redistributed_rather_than_idled() {
        Map<String, Integer> granted = allocator.allocate(
                List.of(demand("small", 2, 1), demand("a", 50, 1), demand("b", 50, 1)), 30);

        assertThat(granted.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(30);
    }

    @Test
    void weight_decides_the_split_when_there_is_not_enough_for_everyone() {
        Map<String, Integer> granted = allocator.allocate(
                List.of(demand("gold", 100, 3), demand("bronze", 100, 1)), 40);

        assertThat(granted.get("gold")).isGreaterThan(granted.get("bronze"));
        // Three to one, within the rounding a whole-number allocation forces.
        assertThat(granted.get("gold")).isBetween(28, 31);
    }

    @Test
    void floors_are_honoured_before_anything_is_shared_out() {
        Map<String, Integer> granted = allocator.allocate(
                List.of(
                        new ConcurrencyDemand("guaranteed", 10, 4, 1),
                        new ConcurrencyDemand("greedy", 100, 1, 10)
                ),
                12);

        assertThat(granted.get("guaranteed")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void a_budget_smaller_than_the_floors_still_lets_every_function_serve() {
        // Granting zero is an admission decision wearing a scheduler's clothes. If the budget
        // cannot cover the floors it is too small for the functions registered, and saying so by
        // silencing one of them is the wrong way to report it.
        Map<String, Integer> granted = allocator.allocate(
                List.of(
                        new ConcurrencyDemand("a", 8, 4, 1),
                        new ConcurrencyDemand("b", 8, 4, 1)
                ),
                2);

        assertThat(granted).hasSize(2);
        assertThat(granted.values()).allSatisfy(limit -> assertThat(limit).isGreaterThanOrEqualTo(1));
    }

    @Test
    void an_empty_platform_allocates_nothing() {
        assertThat(allocator.allocate(List.of(), 100)).isEmpty();
    }

    @Test
    void a_budget_that_divides_unevenly_is_still_fully_used() {
        Map<String, Integer> granted = allocator.allocate(
                List.of(demand("a", 100, 1), demand("b", 100, 1), demand("c", 100, 1)), 10);

        assertThat(granted.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
    }
}
