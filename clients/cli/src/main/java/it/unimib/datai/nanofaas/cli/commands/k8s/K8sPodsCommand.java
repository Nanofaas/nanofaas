package it.unimib.datai.nanofaas.cli.commands.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "pods", description = "List pods for a function.")
public class K8sPodsCommand implements Runnable {

    @ParentCommand
    K8sCommand parent;

    @Parameters(index = "0", description = "Function name")
    String functionName;

    @Override
    public void run() {
        String ns = parent.namespace();
        PodList pods = parent.client().pods().inNamespace(ns).withLabel("function", functionName).list();
        if (pods == null || pods.getItems() == null) {
            return;
        }
        for (Pod p : pods.getItems()) {
            String name = p.getMetadata() == null ? "" : p.getMetadata().getName();
            String phase = p.getStatus() == null ? "" : p.getStatus().getPhase();
            System.out.printf("%s\t%s%n", name, phase);
        }
    }
}
