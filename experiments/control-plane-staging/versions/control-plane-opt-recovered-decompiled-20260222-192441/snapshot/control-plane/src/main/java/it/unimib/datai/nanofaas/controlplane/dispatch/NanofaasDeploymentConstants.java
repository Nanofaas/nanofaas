package it.unimib.datai.nanofaas.controlplane.dispatch;

final class NanofaasDeploymentConstants {
    static final String DEPLOYMENT_NAME_PREFIX = "fn-";
    static final String SERVICE_NAME_PREFIX = "fn-";

    static final String ANNOTATION_SCRAPE = "prometheus.io/scrape";
    static final String ANNOTATION_PATH = "prometheus.io/path";
    static final String ANNOTATION_PORT = "prometheus.io/port";

    private NanofaasDeploymentConstants() {}
}
