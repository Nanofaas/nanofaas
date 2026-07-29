package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.ExposedPorts;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.command.CreateContainerCmdImpl;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

final class DockerJavaRuntimeHints implements RuntimeHintsRegistrar {

    private static final Class<?>[] JACKSON_TYPES = {
            CreateContainerCmdImpl.class,
            CreateContainerResponse.class,
            HostConfig.class,
            ExposedPort.class,
            ExposedPorts.class,
            PortBinding.class,
            Ports.class,
            Ports.Binding.class
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (Class<?> type : JACKSON_TYPES) {
            hints.reflection().registerType(
                    type,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS
            );
        }
    }
}
