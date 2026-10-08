package it.unimib.datai.nanofaas.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Composes the core OpenAPI document with the fragments of the selected control-plane modules. */
@CacheableTask
public abstract class ComposeOpenApiTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCoreDocument();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getFragments();

    @Input
    public abstract ListProperty<String> getModuleIds();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    void compose() {
        Map<String, File> fragmentsByModuleId = new LinkedHashMap<>();
        for (File fragment : getFragments()) {
            fragmentsByModuleId.put(fragment.getParentFile().getName(), fragment);
        }

        Map<String, Path> fragments = new LinkedHashMap<>();
        for (String moduleId : getModuleIds().get()) {
            File fragment = fragmentsByModuleId.get(moduleId);
            if (fragment == null) {
                throw new IllegalStateException("No OpenAPI fragment found for module '" + moduleId + "'");
            }
            fragments.put(moduleId, fragment.toPath());
        }

        OpenApiComposer.compose(getCoreDocument().get().getAsFile().toPath(), fragments,
                getOutputFile().get().getAsFile().toPath());
    }
}
