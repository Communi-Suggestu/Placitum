package com.communi.suggestu.placitum.core.fabric;

import net.fabricmc.loom.LoomNoRemapGradlePlugin;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.util.Constants;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import javax.inject.Inject;

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
        final var includeConfiguration = project.getConfigurations().maybeCreate(Constants.Configurations.INCLUDE);
        final var dependency = project.getDependencies().create(bundleFmjTask);
        includeConfiguration.getDependencies().add(dependency);
    }

    @Override
    protected boolean isObfuscated()
    {
        return false;
    }
}
