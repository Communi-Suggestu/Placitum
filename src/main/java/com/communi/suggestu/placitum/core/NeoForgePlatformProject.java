package com.communi.suggestu.placitum.core;

import net.neoforged.gradle.dsl.common.extensions.AccessTransformers;
import net.neoforged.gradle.dsl.common.extensions.JarJar;
import net.neoforged.gradle.dsl.common.extensions.Minecraft;
import net.neoforged.gradle.dsl.common.extensions.subsystems.Subsystems;
import net.neoforged.gradle.dsl.common.runs.idea.extensions.IdeaRunsExtension;
import net.neoforged.gradle.dsl.common.runs.run.RunManager;
import net.neoforged.gradle.userdev.UserDevPlugin;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.gradle.plugins.ide.idea.model.IdeaProject;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class NeoForgePlatformProject extends CommonPlatformProject {

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void configure(Project project, String coreProjectPath, Set<String> commonProjectPaths, CommonPlatformProject.Platform defaults) {
        super.configure(project, coreProjectPath, commonProjectPaths, defaults);

        project.getPlugins().apply(UserDevPlugin.class);

        commonProjectPaths.add(coreProjectPath);
        final Set<Project> commonProjects = commonProjectPaths.stream()
                .map(project::project)
                .collect(Collectors.toSet());
        final Project coreProject = project.project(coreProjectPath);

        final Platform platform = project.getExtensions().getByType(Platform.class);

        final JarJar jarJar = project.getExtensions().getByType(JarJar.class);
        jarJar.enable();

        for (Project commonProject : commonProjects) {
            final Dependency commonProjectDependency = project.getDependencies().create(commonProject);
            excludeMinecraftDependencies(commonProjectDependency);
            project.getDependencies().add(JavaPlugin.API_CONFIGURATION_NAME, commonProjectDependency);

            jarJar.ranged(commonProjectDependency, "[%s]".formatted(commonProject.getVersion()));
            jarJar.pin(commonProjectDependency, commonProject.getVersion().toString());

            project.getDependencies().add(JarJar.EXTENSION_NAME, commonProjectDependency);
        }

        final Subsystems subsystems = project.getExtensions().getByType(Subsystems.class);
        subsystems.parchment(parchment -> {
            parchment.getMinecraftVersion().set(platform.getParchment().getMinecraftVersion());
            parchment.getMappingsVersion().set(platform.getParchment().getVersion());
        });

        project.getDependencies().addProvider(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, platform.getNeoForge().getVersion().map("net.neoforged:neoforge:%s"::formatted));

        project.getTasks().named(JarJar.EXTENSION_NAME, setArchiveClassifier(""));
        project.getTasks().named(JavaPlugin.JAR_TASK_NAME, setArchiveClassifier("slim"));
        project.getTasks().named(BasePlugin.ASSEMBLE_TASK_NAME, addDependsOn(project.getTasks().named(JarJar.EXTENSION_NAME)));

        project.getConfigurations().named("jarJar", config -> {
            config.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
            config.shouldResolveConsistentlyWith(project.getConfigurations().getByName("runtimeClasspath"));
        });
        final Configuration includedLibraries = project.getConfigurations().create("includedLibraries", config -> {
            config.setTransitive(false);
            config.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
        });
        project.getConfigurations().getByName("implementation").extendsFrom(includedLibraries);
        project.getConfigurations().getByName("jarJar").extendsFrom(includedLibraries);

        final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        final RunManager runs = project.getExtensions().getByType(RunManager.class);
        sourceSets.configureEach(sourceSet -> {
            final String configName = sourceSet.getTaskName(null, "ForgeLibrary");

            final Configuration library = project.getConfigurations().maybeCreate(configName);
            final Configuration implementation = project.getConfigurations().getByName(sourceSet.getImplementationConfigurationName());

            implementation.extendsFrom(library);
            includedLibraries.extendsFrom(library);

            runs.configureEach(run -> {
                run.getDependencies().getRuntime().add(library);
            });
        });
        
        final AccessTransformers accessTransformers = project.getExtensions().getByType(Minecraft.class).getAccessTransformers();
        accessTransformers.getFiles().from(platform.getNeoForge().getAccessTransformers());
        
        project.getDependencies().addProvider("implementation", platform.getNeoForge().getVersion()
                .map("net.neoforged:neoforge:%s"::formatted)
                .map(project.getDependencies()::create)
                .orElse(project.provider(() -> {
                    throw getProblems().getReporter().throwing(spec -> {
                        spec.id("placitum-missing-neoforge-version", "NeoForge version is not configured");
                        spec.details("NeoForge version is not configured");
                        spec.solution("Set the NeoForge version in the platform configuration: platform.neoforge.version");
                        spec.withException(new InvalidUserDataException("NeoForge version is not configured"));
                    });
                })));
        
        project.getTasks().named("processResources", ProcessResources.class, processResources -> {
            processResources.from(platform.getNeoForge().getAccessTransformers(), spec -> spec.into("META-INF"));
        });
        
        runs.register("client");
        runs.register("server");
        runs.register("data", run -> {
            run.getArguments().addAll(List.of(
                    "--mod", "${project.modId.toLowerCase()}",
                    "--output", coreProject.file("src/datagen/generated").getAbsolutePath()
            ));
            run.getArguments().addAll(
                    commonProjects.stream()
                            .map(p -> p.file("src/main/resources").getAbsolutePath())
                            .flatMap(f -> Stream.of("--existing", f))
                            .toList()
            );
        });

        runs.configureEach(run -> {
            run.modSource(sourceSets.getByName("main"));
            run.modSources(
                    commonProjects.stream().map(p -> p.getExtensions().getByType(SourceSetContainer.class))
                            .map(ss -> ss.getByName("main"))
                            .toList()
            );
        });

        final TaskProvider<net.neoforged.gradle.common.tasks.JarJar> jarJarTask = project.getTasks().named(JarJar.EXTENSION_NAME, net.neoforged.gradle.common.tasks.JarJar.class);
        jarJarTask.configure(task -> {
            task.getArchiveClassifier().set("");
        });

        final TaskProvider<Jar> jarTask = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
        jarTask.configure(task -> {
            task.getArchiveClassifier().set("slim");
        });

        project.getTasks().named(BasePlugin.ASSEMBLE_TASK_NAME, task -> {
            task.dependsOn(jarJarTask);
        });

        if (isRunningWithIdea(project)) {
            final Project rootProject = project.getRootProject();
            final IdeaModel ideaModel = rootProject.getExtensions().getByType(IdeaModel.class);
            final IdeaProject ideaProject = ideaModel.getProject();
            final IdeaRunsExtension ideaRunsExtension = ((ExtensionAware) ideaProject).getExtensions().getByType(IdeaRunsExtension.class);
            ideaRunsExtension.getRunWithIdea().set(true);
        }
    }

    private Action<Task> setArchiveClassifier(String classifier) {
        return task -> {
            if (task instanceof AbstractArchiveTask archiveTask) {
                archiveTask.getArchiveClassifier().set(classifier);
            }
        };
    }

    private Action<Task> addDependsOn(TaskProvider<?>... task) {
        return t -> {
            for (TaskProvider<?> taskProvider : task) {
                t.dependsOn(taskProvider);
            }
        };
    }

    @Inject
    protected abstract Problems getProblems();

    @Override
    protected Platform registerPlatformExtension(Project project, CommonPlatformProject.Platform defaults) {
        return project.getExtensions().create(Platform.class, CommonPlatformProject.Platform.EXTENSION_NAME, Platform.class, project, defaults);
    }

    @Override
    protected Provider<String> getLoaderVersion(CommonPlatformProject.Platform platform) {
        if (platform instanceof Platform neoforgePlatform) {
            return neoforgePlatform.getNeoForge().getVersion();
        }

        throw new GradleException("Platform is not an instance of PlatformNeoForge");
    }

    @Override
    protected Map<String, ?> getInterpolatedProperties(CommonPlatformProject.Platform platform) {
        if (platform instanceof Platform neoforgePlatform) {
            return Map.of("dependenciesNeoforgeNpm", neoforgePlatform.getNeoForge().getVersion()
                    .map(version -> createSupportedVersionRange(version, true)),
                    "dependenciesNeoforgeMaven", neoforgePlatform.getNeoForge().getVersion()
                            .map(version -> createSupportedVersionRange(version, false))
            );

        } else {
            throw new GradleException("Platform is not an instance of PlatformNeoForge");
        }
    }

    @Override
    protected Set<Configuration> getDependencyInterpolationConfigurations(Project project) {
        return Set.of(
                project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME),
                project.getConfigurations().getByName(JavaPlugin.API_CONFIGURATION_NAME)
        );
    }

    public abstract static class Platform extends CommonPlatformProject.Platform {

        private final PlatformNeoForge neoForge;

        @Inject
        public Platform(Project project, CommonPlatformProject.Platform settings) {
            super(project, settings);

            this.neoForge = project.getExtensions().create("neoforge", PlatformNeoForge.class);
        }

        public static abstract class PlatformNeoForge {

            @Inject
            public PlatformNeoForge(Project project) {
                getVersion().convention(project.getProviders().gradleProperty("neoforge.version").map(String::trim));
            }

            @Input
            public abstract Property<String> getVersion();

            @InputFiles
            @PathSensitive(PathSensitivity.NONE)
            public abstract ConfigurableFileCollection getAccessTransformers();
        }

        public PlatformNeoForge getNeoForge() {
            return neoForge;
        }

        public void neoforge(Action<? super PlatformNeoForge> action) {
            action.execute(getNeoForge());
        }
    }
}
