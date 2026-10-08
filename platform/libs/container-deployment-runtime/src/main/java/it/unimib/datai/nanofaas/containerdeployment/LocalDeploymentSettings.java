package it.unimib.datai.nanofaas.containerdeployment;

import java.time.Duration;

public record LocalDeploymentSettings(String callbackUrl, Duration readinessTimeout,
                                      Duration readinessPollInterval) {
}
