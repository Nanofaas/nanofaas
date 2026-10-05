package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerHostConfig;
import com.github.dockerjava.api.model.ContainerMount;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.ExposedPorts;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.ResponseItem;
import com.github.dockerjava.core.command.CreateContainerCmdImpl;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

final class DockerJavaRuntimeHints implements RuntimeHintsRegistrar {

    private static final Class<?>[] JACKSON_TYPES = {
            com.github.dockerjava.core.DockerConfigFile.class,
            com.github.dockerjava.api.model.AuthConfig.class,
            com.github.dockerjava.api.model.Image.class,
            CreateContainerCmdImpl.class,
            CreateContainerResponse.class,
            com.github.dockerjava.api.command.InspectImageResponse.class,
            com.github.dockerjava.api.command.GraphDriver.class,
            com.github.dockerjava.api.command.GraphData.class,
            com.github.dockerjava.api.command.RootFS.class,
            com.github.dockerjava.api.model.ContainerConfig.class,
            com.github.dockerjava.api.model.HealthCheck.class,
            HostConfig.class,
            ExposedPort.class,
            ExposedPorts.class,
            PortBinding.class,
            Ports.class,
            Ports.Binding.class,
            PullResponseItem.class,
            ResponseItem.class,
            ResponseItem.ProgressDetail.class,
            ResponseItem.ErrorDetail.class,
            ResponseItem.AuxDetail.class,
            Container.class,
            ContainerPort.class,
            ContainerHostConfig.class,
            ContainerNetworkSettings.class,
            ContainerNetwork.class,
            ContainerNetwork.Ipam.class,
            ContainerMount.class
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Jackson binds the package-private currentContext setter when an existing config file is present.
        hints.reflection().registerType(com.github.dockerjava.core.DockerConfigFile.class,
                MemberCategory.INVOKE_DECLARED_METHODS);
        for (Class<?> type : JACKSON_TYPES) {
            hints.reflection().registerType(
                    type,
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS
            );
        }
    }
}
