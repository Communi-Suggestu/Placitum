package com.communi.suggestu.placitum.core;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.task.RemapJarTask;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class FabricPlatformProject extends AbstractPlatformProject {

    @Inject
    public FabricPlatformProject() {
        super();
    }

    @Inject
    public abstract ArchiveOperations getArchiveOperations();

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void configure(Project project, String coreProjectPath, Set<String> commonProjectPaths, AbstractPlatformProject.Platform defaults) {
        super.configure(project, coreProjectPath, commonProjectPaths, defaults);

        project.getPlugins().apply(LoomGradlePlugin.class);

        commonProjectPaths.add(coreProjectPath);
        final Set<Project> commonProjects = commonProjectPaths.stream()
                .map(project::project)
                .collect(Collectors.toSet());

        final Platform platform = project.getExtensions().getByType(Platform.class);

        for (Project commonProject : commonProjects) {
            final Dependency commonProjectDependency = project.getDependencies().create(commonProject);
            excludeMinecraftDependencies(commonProjectDependency);

            final Configuration apiConfiguration = project.getConfigurations().getByName(JavaPlugin.API_CONFIGURATION_NAME);
            apiConfiguration.getDependencies().add(commonProjectDependency);

            final String commonProjectName = commonProject.getName();
            final String commonProjectGroup = commonProject.getGroup().toString();
            final String rootProjectName = commonProject.getRootProject().getName();
            final String commonProjectVersion = commonProject.getVersion().toString();

            Provider<@NotNull File> metadataGenerationFile = project.getLayout().getBuildDirectory()
                    .dir("generated/fabric/metadata/placitum/projects/core/%s".formatted(commonProjectName))
                    .map(dir -> dir.file("fabric.mod.json"))
                    .map(file -> {
                        final File targetFile = file.getAsFile();
                        final File directory = targetFile.getParentFile();
                        if (!directory.exists() && !directory.mkdirs()) {
                            throw new GradleException("Failed to create directory: %s".formatted(directory));
                        }

                        try {
                            Files.writeString(targetFile.toPath(), """
                                    {
                                      "schemaVersion": 1,
                                      "id": "%s",
                                      "version": "%s",
                                      "name": "%s",
                                      "custom": {
                                        "fabric-loom:generated": true
                                      }
                                    }
                                    """.formatted(
                                    "%s_%s".formatted(commonProjectGroup.replace(".", "_"), commonProjectName),
                                    commonProjectVersion,
                                    "%s - %s".formatted(rootProjectName, commonProjectName)
                            ));
                        } catch (IOException e) {
                            throw new GradleException("Failed to write metadata file: %s".formatted(targetFile), e);
                        }

                        return targetFile;
                    });

            final TaskProvider<@NotNull Jar> jarTask = commonProject.getTasks().named("jar", Jar.class);

            final Provider<@NotNull FileTree> compiledJarTree = jarTask.flatMap(Jar::getArchiveFile).map(getArchiveOperations()::zipTree);
            final TaskProvider<@NotNull Jar> bundleFmjTask = project.getTasks().register("bundleFmj%s".formatted(commonProject.getName()), Jar.class, task -> {
                task.from(compiledJarTree);
                task.from(metadataGenerationFile);
                task.getArchiveClassifier().set("%s-bundled".formatted(commonProjectName));
            });

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

        final LoomGradleExtensionAPI loom = project.getExtensions().getByType(LoomGradleExtensionAPI.class);

        project.getDependencies().addProvider("mappings",
                platform.getParchment().getMinecraftVersion().zip(
                        platform.getParchment().getVersion(),
                        "org.parchmentmc.data:parchment-%s:%s@zip"::formatted
                ).map(parchment -> loom.layered(layer -> {
                    layer.officialMojangMappings();
                    layer.parchment(parchment);
                })));

        loom.getAccessWidenerPath().set(platform.getFabric().getAccessWideners().map(
                file -> {
                    if (file.getAsFile().exists())
                        return file;

                    return null;
                }
        ));
        project.getTasks().named("processResources", ProcessResources.class, processResources -> {
            processResources.from(platform.getFabric().getAccessWideners());
        });

        project.getTasks().named("remapJar", RemapJarTask.class, task -> {
            task.getAddNestedDependencies().set(true);
        });

        loom.getRuns().named("client", client -> {
            client.client();
            client.setConfigName("Fabric Client");
            client.ideConfigGenerated(true);
            client.runDir("runs/client");
        });
        loom.getRuns().named("server", server -> {
            server.server();
            server.setConfigName("Fabric Server");
            server.ideConfigGenerated(true);
            server.runDir("runs/server");
        });

        final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        loom.getMods().register(project.getRootProject().getName(), mod -> {
            commonProjects.forEach(p -> {
                final SourceSetContainer projectSourceSets = p.getExtensions().getByType(SourceSetContainer.class);
                mod.sourceSet(projectSourceSets.getByName("main"), p);
            });
            mod.sourceSet(sourceSets.getByName("main"), project);
        });

        if (isRunningWithIdea(project)) {
            //We are in a special mode that requires us to redirect the process resources tasks to the idea out directory.
            //We only care for our own output for now, dependency projects will need to be handled separately.
            var copyIdeaResources = project.getTasks().register("copyIdeaResources", Copy.class, copy -> {
                copy.from(project.getTasks().named("processResources", ProcessResources.class).map(ProcessResources::getDestinationDir));
                copy.into(project.file("out/production/resources"));
            });

            project.getTasks().named("processResources", ProcessResources.class, processResources -> processResources.finalizedBy(copyIdeaResources));
        }

        final String relativeProjectDirectory = project.getRootDir().toPath().relativize(project.getProjectDir().toPath()).toString();
        final TaskProvider<@NotNull Task> ideaSyncRegistrar = project.getTasks().register("ideaSyncRunModifier", task -> {
            task.doLast(t -> {
                final File runConfigurationsDir = new File(project.getRootDir(), ".idea/runConfigurations");
                for (File file : Objects.requireNonNull(runConfigurationsDir.listFiles())) {
                    if (!file.getName().endsWith(".xml")) {
                        continue;
                    }

                    try {
                        final String content = Files.readString(file.toPath());
                        final String targetContent = "<option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"processResources\" externalProjectPath=\"$PROJECT_DIR$/%s\" vmOptions=\"\" scriptParameters=\"-PrunsWithIdea=true\" />".formatted(relativeProjectDirectory);
                        final String alternativeTarget = "<option enabled=\"true\" externalProjectPath=\"$PROJECT_DIR$/%s\" name=\"Gradle.BeforeRunTask\" scriptParameters=\"-PrunsWithIdea=true\" tasks=\"processResources\" vmOptions=\"\"/>".formatted(relativeProjectDirectory);
                        final String beforeMarker = "</method>";

                        if (content.contains(targetContent) || content.contains(alternativeTarget)) {
                            continue;
                        }

                        final String[] contentParts = content.split(beforeMarker);
                        if (contentParts.length != 2) {
                            continue;
                        }

                        final String newContent = "%s%s%s%s".formatted(contentParts[0], targetContent, beforeMarker, contentParts[1]);
                        Files.writeString(file.toPath(), newContent);
                    } catch (IOException e) {
                        throw new GradleException("Failed to read or write file: %s".formatted(file), e);
                    }
                }
            });
        });

        project.getTasks().named("ideaSyncTask", idea -> {
            idea.finalizedBy(ideaSyncRegistrar);
        });
    }

    @Override
    protected Platform registerPlatformExtension(Project project, AbstractPlatformProject.Platform defaults) {
        return project.getExtensions().create(Platform.class, AbstractPlatformProject.Platform.EXTENSION_NAME, Platform.class, project, defaults);
    }

    @Override
    protected Provider<@NotNull String> getLoaderVersion(AbstractPlatformProject.Platform platform) {
        if (platform instanceof Platform fabricPlatform) {
            return fabricPlatform.getFabric().getLoaderVersion();
        } else {
            throw new GradleException("Platform is not an instance of PlatformFabric");
        }
    }

    @Override
    protected Map<String, ?> getInterpolatedProperties(AbstractPlatformProject.Platform platform) {
        if (platform instanceof Platform fabricPlatform) {
            return Map.of(
                    "dependenciesFabricLoaderNpm", fabricPlatform.getFabric().getLoaderVersion()
                            .map(version -> AbstractPlatformProject.createSupportedVersionRange(version, true)),
                    "dependenciesFabricApiNpm", fabricPlatform.getFabric().getApiVersion()
                            .map(version -> AbstractPlatformProject.createSupportedVersionRange(version, true))
            );
        } else {
            throw new GradleException("Platform is not an instance of PlatformFabric");
        }
    }

    @Override
    protected Set<Configuration> getDependencyInterpolationConfigurations(Project project) {
        return Set.of(
                project.getConfigurations().getByName(JavaPlugin.API_CONFIGURATION_NAME),
                project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME),
                project.getConfigurations().getByName("modImplementation")
        );
    }

    public abstract static class Platform extends AbstractPlatformProject.Platform {

        private final PlatformFabric fabric;

        @Inject
        public Platform(Project project, AbstractPlatformProject.Platform settings) {
            super(project, settings);

            this.fabric = project.getExtensions().create("fabric", PlatformFabric.class);
        }

        public static abstract class PlatformFabric {

            @Inject
            public PlatformFabric(Project project, Platform platform) {
                getLoaderVersion().convention(project.getProviders().gradleProperty("fabric.loader.version").map(String::trim));
                getApiVersion().convention(project.getProviders().gradleProperty("fabric.api.version").map(String::trim));
                getFabricApiMinecraftVersion().convention(project.getProviders().gradleProperty("fabric.api.minecraft.version").map(String::trim).orElse(platform.getMinecraft().getVersion()));
                getAccessWideners().convention(project.getRootProject().getLayout().getProjectDirectory().dir("common").file("%s.accesswidener".formatted(project.getRootProject().getName().toLowerCase(Locale.ROOT))));
            }

            @Input
            public abstract Property<@NotNull String> getLoaderVersion();

            @Input
            public abstract Property<@NotNull String> getApiVersion();

            @Input
            public abstract Property<@NotNull String> getFabricApiMinecraftVersion();

            @InputFile
            @PathSensitive(PathSensitivity.NONE)
            @Optional
            public abstract RegularFileProperty getAccessWideners();
        }

        public PlatformFabric getFabric() {
            return fabric;
        }

        public void fabric(Action<? super PlatformFabric> action) {
            action.execute(getFabric());
        }
    }
}
