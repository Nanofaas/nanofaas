package it.unimib.datai.nanofaas.cli.commands.k8s;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "describe", description = "Describe nanofaas resources for a function.")
public class K8sDescribeCommand implements Runnable {

    @ParentCommand
    K8sCommand parent;

    @Parameters(index = "0", description = "Function name")
    String functionName;

    @Override
    public void run() {
        String ns = parent.namespace();
        String name = "fn-" + functionName;

        Deployment d = parent.client().apps().deployments().inNamespace(ns).withName(name).get();
        Service s = parent.client().services().inNamespace(ns).withName(name).get();
        HorizontalPodAutoscaler hpa = parent.client().autoscaling().v2().horizontalPodAutoscalers().inNamespace(ns).withName(name).get();

        System.out.printf("deployment\t%s\t%s%n", name, d == null ? "missing" : "present");
        System.out.printf("service\t%s\t%s%n", name, s == null ? "missing" : "present");
        System.out.printf("hpa\t%s\t%s%n", name, hpa == null ? "missing" : "present");
    }
}
