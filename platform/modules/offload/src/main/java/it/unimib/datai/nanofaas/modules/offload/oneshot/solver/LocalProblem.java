package it.unimib.datai.nanofaas.modules.offload.oneshot.solver;
import java.util.List;
/** Ordered functions and exact integer MiB budget; quantities are already on the flow grid. */
public record LocalProblem(Model model, long memoryCapacityMiB, List<Function> functions) {
    public enum Model { LSP, LSPr_x }
    public record Function(String id, double load, double demandSeconds, double utilization, long memoryMiB,
                           double alpha, double delta, double gamma, double price,
                           double fixedLocal, double fixedOffload, double inbound) {}
    public LocalProblem { if (functions != null) functions = List.copyOf(functions); }
}
