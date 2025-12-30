package com.communi.suggestu.placitum.core.fabric;

import net.fabricmc.loom.LoomNoRemapGradlePlugin;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.task.RemapJarTask;
import net.fabricmc.loom.util.Constants;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.util.Map;

public abstract class NoneRemappingFabricPlatformProject extends AbstractFabricPlatformProject
{
    @Inject
    public NoneRemappingFabricPlatformProject() {
        super();
    }

    protected void applyLoomPlugin(final Project project)
    {
        project.getPlugins().apply(LoomNoRemapGradlePlugin.class);
    }

    @Override
    protected void enableProjectDependenciesNesting(final Project project)
    {
        //Noop, it is enabled by default it seems.
    }

    @Override
    protected void setupMappings(final Project project, final Platform platform, final LoomGradleExtensionAPI loom)
    {
        //Noop, we are in deobfuscated land :P
    }

    @Override
    protected void setupMinecraftAndFabricDependencies(final Project project, final Platform platform)
    {
        project.getDependencies().addProvider("minecraft", platform.getMinecraft().getVersion()
            .map("com.mojang:minecraft:%s"::formatted));
        project.getDependencies().addProvider("implementation", platform.getFabric().getLoaderVersion()
            .map("net.fabricmc:fabric-loader:%s"::formatted));
        project.getDependencies().addProvider("implementation",
            platform.getFabric().getApiVersion().zip(
                platform.getFabric().getFabricApiMinecraftVersion(),
                "net.fabricmc.fabric-api:fabric-api:%s+%s"::formatted
            ));
    }

    @Override
    protected void includeAndExposeCommonProject(final Project project, final Project commonProject, final TaskProvider<Jar> bundleFmjTask, final String commonProjectName)
    {

        final Configuration include = project.getConfigurations().maybeCreate(Constants.Configurations.INCLUDE);
        include.getDependencies().add(
            project.getDependencies().project(Map.of(
                "path", commonProject.getPath(),
                "configuration", "bundledElements"
            ))
        );

        final Configuration commonBundledElements = commonProject.getConfigurations().maybeCreate("bundledElements");
        commonProject.getArtifacts().add("bundledElements", bundleFmjTask.flatMap(Jar::getArchiveFile), artifact -> {
            artifact.builtBy(bundleFmjTask);
            artifact.setType("jar");
        });

        commonProject.getComponents().named("java", AdhocComponentWithVariants.class, component -> {
            component.addVariantsFromConfiguration(commonBundledElements, variant -> {
                variant.mapToMavenScope("runtime");
                variant.mapToOptional();
            });
        });
    }

    @Override
    protected boolean isObfuscated()
    {
        return false;
    }
}
