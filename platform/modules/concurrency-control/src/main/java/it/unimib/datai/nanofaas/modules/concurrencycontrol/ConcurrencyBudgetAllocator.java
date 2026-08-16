package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Divides a fixed concurrency budget among the functions asking for it.
 *
 * <p>Weighted max-min fairness, the allocation used for link bandwidth since the eighties and the
 * ancestor of what cluster schedulers do with DRF. Everyone gets their ask if the budget covers the
 * total; otherwise each function is held to its weighted share, and whatever a modest function
 * leaves unclaimed is redistributed among those still short, repeatedly until nothing more can
 * move.</p>
 *
 * <p>Two properties are the reason for choosing it over simply scaling every ask by the same
 * factor. A function asking for less than its share is never cut, so a small function is not
 * punished for sharing a platform with a large one. And nothing is left on the table while someone
 * is still short — proportional scaling would idle capacity that a starved function could use.</p>
 *
 * <p>The floors are honoured before anything else. A function granted zero cannot serve, and a
 * platform that starves a function into total silence to satisfy a neighbour has made an admission
 * decision while pretending to make a scheduling one. If the floors alone exceed the budget, the
 * budget is by definition too small for the functions registered, and every floor is granted: the
 * alternative is to break a promise the platform makes to every tenant in order to keep the
 * arithmetic tidy.</p>
 */
public final class ConcurrencyBudgetAllocator {

    public Map<String, Integer> allocate(List<ConcurrencyDemand> demands, int budget) {
        Map<String, Integer> granted = new HashMap<>();
        if (demands.isEmpty()) {
            return granted;
        }
        for (ConcurrencyDemand demand : demands) {
            granted.put(demand.functionName(), demand.floor());
        }

        int remaining = budget - demands.stream().mapToInt(ConcurrencyDemand::floor).sum();
        List<ConcurrencyDemand> unsatisfied = stillShort(demands, granted);
        while (remaining > 0 && !unsatisfied.isEmpty()) {
            int distributed = shareByWeight(unsatisfied, granted, remaining);
            remaining -= distributed;
            if (distributed == 0) {
                // Shares have rounded down to nothing: hand the remainder out one at a time so a
                // budget that does not divide evenly is still spent rather than looped on.
                handOutSingly(unsatisfied, granted, remaining);
                break;
            }
            unsatisfied = stillShort(unsatisfied, granted);
        }
        return granted;
    }

    private static List<ConcurrencyDemand> stillShort(
            List<ConcurrencyDemand> demands, Map<String, Integer> granted) {
        List<ConcurrencyDemand> shortfall = new ArrayList<>(demands.size());
        for (ConcurrencyDemand demand : demands) {
            if (granted.getOrDefault(demand.functionName(), 0) < demand.desired()) {
                shortfall.add(demand);
            }
        }
        return shortfall;
    }

    private static int shareByWeight(
            List<ConcurrencyDemand> unsatisfied, Map<String, Integer> granted, int remaining) {
        double totalWeight = unsatisfied.stream().mapToDouble(ConcurrencyDemand::weight).sum();
        int distributed = 0;
        for (ConcurrencyDemand demand : unsatisfied) {
            int current = granted.get(demand.functionName());
            int share = (int) Math.floor(remaining * demand.weight() / totalWeight);
            int take = Math.min(share, demand.desired() - current);
            if (take > 0) {
                granted.put(demand.functionName(), current + take);
                distributed += take;
            }
        }
        return distributed;
    }

    private static void handOutSingly(
            List<ConcurrencyDemand> unsatisfied, Map<String, Integer> granted, int remaining) {
        int handedOut = 0;
        for (ConcurrencyDemand demand : unsatisfied) {
            if (handedOut >= remaining) {
                break;
            }
            int current = granted.get(demand.functionName());
            if (current < demand.desired()) {
                granted.put(demand.functionName(), current + 1);
                handedOut++;
            }
        }
    }
}
