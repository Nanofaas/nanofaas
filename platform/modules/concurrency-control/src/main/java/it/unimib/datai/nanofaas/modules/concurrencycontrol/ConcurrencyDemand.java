package it.unimib.datai.nanofaas.modules.concurrencycontrol;

/**
 * What one function is asking the platform for on this tick.
 *
 * <p>Separating the ask from the grant is the point of the mode. A controller that decides its own
 * limit is deciding how to share a resource it cannot see, and the measured consequence is a
 * function's limit moving because a neighbour arrived. Here the function states a need and the
 * allocator answers with what is available.</p>
 *
 * @param desired the limit that would meet this function's latency SLO at the load it is seeing
 * @param floor   the least it can be given and still be able to serve anything
 * @param weight  its claim when the budget cannot satisfy every ask
 */
public record ConcurrencyDemand(String functionName, int desired, int floor, double weight) {
    public ConcurrencyDemand {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("functionName is required");
        }
        floor = Math.max(1, floor);
        desired = Math.max(floor, desired);
        weight = weight > 0 ? weight : 1.0;
    }
}
