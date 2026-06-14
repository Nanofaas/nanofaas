package it.unimib.datai.nanofaas.cli.commands.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodList;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.Comparator;

@Command(name = "logs", description = "Show logs for a function pod.")
public class K8sLogsCommand implements Runnable {

    @ParentCommand
    K8sCommand parent;

    @Parameters(index = "0", description = "Function name")
    String functionName;

    @Option(names = {"--container"}, description = "Container name (default: function)")
    String container = "function";

    @Override
    public void run() {
        String ns = parent.namespace();
        PodList pods = parent.client().pods().inNamespace(ns).withLabel("function", functionName).list();
        if (pods == null || pods.getItems() == null || pods.getItems().isEmpty()) {
            throw new IllegalArgumentException("No pods found for function=" + functionName + " in namespace=" + ns);
        }

        Pod selected = pods.getItems().stream()
                .sorted(Comparator.comparing(K8sLogsCommand::readyRank)
                        .thenComparing(p -> p.getMetadata() == null ? "" : String.valueOf(p.getMetadata().getCreationTimestamp()), Comparator.reverseOrder()))
                .findFirst()
                .orElseThrow();

        String podName = selected.getMetadata().getName();
        String log = parent.client().pods().inNamespace(ns).withName(podName).inContainer(container).getLog();
        System.out.print(log);
    }

    private static int readyRank(Pod p) {
        // 0 = ready, 1 = not ready
        if (p == null || p.getStatus() == null || p.getStatus().getConditions() == null) {
            return 1;
        }
        for (PodCondition c : p.getStatus().getConditions()) {
            if ("Ready".equals(c.getType()) && "True".equalsIgnoreCase(c.getStatus())) {
                return 0;
            }
        }
        return 1;
    }
}
