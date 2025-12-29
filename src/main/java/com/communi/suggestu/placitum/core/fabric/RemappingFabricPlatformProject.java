package com.communi.suggestu.placitum.core.fabric;

import net.fabricmc.loom.LoomRemapGradlePlugin;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.task.RemapJarTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.util.Map;

public abstract class RemappingFabricPlatformProject extends AbstractFabricPlatformProject
{

    @Inject
    public RemappingFabricPlatformProject() {
        super();
    }

    protected void enableProjectDependenciesNesting(final Project project)
    {
        project.getTasks().named("remapJar", RemapJarTask.class, task -> {
            task.getAddNestedDependencies().set(true);
        });
    }

    protected void applyLoomPlugin(final Project project)
    {
        project.getPlugins().apply(LoomRemapGradlePlugin.class);
    }

    @SuppressWarnings("UnstableApiUsage")
    protected void setupMappings(final Project project, final Platform platform, final LoomGradleExtensionAPI loom)
    {
        project.getDependencies().addProvider("mappings",
            platform.getParchment().getMinecraftVersion().zip(
                platform.getParchment().getVersion(),
                "org.parchmentmc.data:parchment-%s:%s@zip"::formatted
            ).map(parchment -> loom.layered(layer -> {
                layer.officialMojangMappings();
                layer.parchment(parchment);
            })));
    }

    protected void setupMinecraftAndFabricDependencies(final Project project, final Platform platform)
    {
        final Attribute<@NotNull Boolean> remappedAttribute = Attribute.of("net.fabric.loom.remapped", Boolean.class);
        project.getConfigurations().matching(config -> config.getName().startsWith("mod")).configureEach(config -> {
            config.getAttributes().attribute(remappedAttribute, true);
        });

        project.getDependencies().addProvider("minecraft", platform.getMinecraft().getVersion()
            .map("com.mojang:minecraft:%s"::formatted));
        project.getDependencies().addProvider("modImplementation", platform.getFabric().getLoaderVersion()
            .map("net.fabricmc:fabric-loader:%s"::formatted));
        project.getDependencies().addProvider("modImplementation",
            platform.getFabric().getApiVersion().zip(
                platform.getFabric().getFabricApiMinecraftVersion(),
                "net.fabricmc.fabric-api:fabric-api:%s+%s"::formatted
            ));
    }

    protected void includeAndExposeCommonProject(final Project project, final Project commonProject, final TaskProvider<@NotNull Jar> bundleFmjTask, final String commonProjectName)
    {
        final TaskProvider<@NotNull RemapJarTask> remapBundledTask = project.getTasks().register("remapBundled%s".formatted(commonProject.getName()), RemapJarTask.class, task -> {
            task.dependsOn(bundleFmjTask);
            task.getInputFile().set(bundleFmjTask.flatMap(Jar::getArchiveFile));
            task.getArchiveClassifier().set("%s-remapped".formatted(commonProjectName));
        });

        project.getTasks().named("remapJar", RemapJarTask.class, task -> {
            task.getNestedJars().from(remapBundledTask.flatMap(RemapJarTask::getArchiveFile));
            task.dependsOn(remapBundledTask);
        });

        final Configuration outgoingRemappedElements = project.getConfigurations().maybeCreate("remappedRuntimeElements");
        outgoingRemappedElements.setCanBeResolved(false);
        outgoingRemappedElements.getDependencies().add(
            project.getDependencies().project(Map.of(
                "path", commonProject.getPath(),
                "configuration", "runtimeElements"
            ))
        );

        final Attribute<@NotNull Boolean> remappedAttribute = Attribute.of("net.fabric.loom.remapped", Boolean.class);
        outgoingRemappedElements.getAttributes().attribute(remappedAttribute, true);

        commonProject.getComponents().named("java", AdhocComponentWithVariants.class, component -> {
            component.addVariantsFromConfiguration(outgoingRemappedElements, variant -> {
                variant.mapToMavenScope("runtime");
                variant.mapToOptional();
            });
        });

        commonProject.getConfigurations().maybeCreate("remappedRuntimeElements");
        commonProject.getArtifacts().add("remappedRuntimeElements", remapBundledTask.flatMap(RemapJarTask::getArchiveFile), artifact -> {
            artifact.builtBy(remapBundledTask);
            artifact.setType("jar");
        });
    }

    @Override
    protected boolean isObfuscated()
    {
        return true;
    }
}
