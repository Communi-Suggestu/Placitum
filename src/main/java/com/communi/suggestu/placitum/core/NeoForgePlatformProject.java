package com.communi.suggestu.placitum.core;

import net.neoforged.gradle.dsl.common.extensions.JarJar;
import net.neoforged.gradle.dsl.common.extensions.subsystems.Subsystems;
import net.neoforged.gradle.userdev.UserDevPlugin;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.language.jvm.tasks.ProcessResources;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public final class NeoForgePlatformProject extends CommonPlatformProject {

    private static void excludeMinecraftDependencies(ExternalModuleDependency dependency) {
        dependency.exclude(Map.of(
                "group", "net.minecraft",
                "module", "client"
        ));
        dependency.exclude(Map.of(
                "group", "net.minecraft",
                "module", "server"
        ));
        dependency.exclude(Map.of(
                "group", "net.minecraft",
                "module", "joined"
        ));
    }

    private record ValueCallable<T>(T value) implements Callable<T> {
        @Override
        public T call() {
            return value;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void configure(Project project, Set<String> coreProjects) {
        super.configure(project, coreProjects);

        project.getPlugins().apply(UserDevPlugin.class);

        final Set<Project> projects = coreProjects.stream()
                .map(project::project)
                .collect(Collectors.toSet());

        final Platform platform = project.getExtensions().getByType(Platform.class);

        for (Project coreProject : projects) {
            final Provider<Project> coreProjectProvider = coreProject.provider(new ValueCallable<>(coreProject));

            project.getDependencies().addProvider(JavaPlugin.API_CONFIGURATION_NAME, coreProjectProvider, NeoForgePlatformProject::excludeMinecraftDependencies);

            final JarJar jarJar = project.getExtensions().getByType(JarJar.class);
            jarJar.enable();

            project.getDependencies().addProvider(JarJar.EXTENSION_NAME, coreProjectProvider, dependency -> {
                jarJar.ranged(dependency, "[%s]".formatted(coreProject.getVersion()));
                jarJar.pin(dependency, coreProject.getVersion().toString());

                excludeMinecraftDependencies(dependency);
            });
        }

        final Subsystems subsystems = project.getExtensions().getByType(Subsystems.class);
        subsystems.parchment(parchment -> {
            parchment.getMinecraftVersion().set(platform.getParchment().getMinecraftVersion());
            parchment.getMappingsVersion().set(platform.getParchment().getVersion());
        });

        project.getDependencies().addProvider(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, platform.getNeoForge().getVersion().map("net.neoforged:neoforge:%s"::formatted));

        project.getTasks().named("processResources", ProcessResources.class, task -> {
            final Map<String, ?> interpolate = Map.of(
                    "version", project.getVersion(),
                    "minecraftVersion", platform.getParchment().getMinecraftVersion(),
                    "loaderVersion", platform.getNeoForge().getVersion()
            );

            task.getInputs().properties(interpolate);

            task.filesMatching(List.of("/META-INF/mods.toml", "/META-INF/neoforge.mods.toml"), fileCopyDetails -> {
                fileCopyDetails.expand(interpolate);
            });
        });

        project.getTasks().named(JarJar.EXTENSION_NAME, setArchiveClassifier(""));
        project.getTasks().named(JavaPlugin.JAR_TASK_NAME, setArchiveClassifier("slim"));
        project.getTasks().named(BasePlugin.ASSEMBLE_TASK_NAME, addDependsOn(project.getTasks().named(JarJar.EXTENSION_NAME)));
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

    @Override
    protected Platform registerPlatformExtension(Project project) {
        return project.getExtensions().create(Platform.class, CommonPlatformProject.Platform.EXTENSION_NAME, Platform.class, project);
    }

    public static class Platform extends CommonPlatformProject.Platform {

        private final PlatformNeoForge neoForge;

        public Platform(Project project) {
            super(project);

            this.neoForge = project.getExtensions().create(PlatformNeoForge.class, "neoforge", PlatformNeoForge.class);
        }

        public static abstract class PlatformNeoForge {

             @Inject
            public PlatformNeoForge(Project project) {
                 getVersion().convention(project.getProviders().gradleProperty("neoforge.version").map(String::trim));
            }

            @Input
            public abstract Property<String> getVersion();
        }

        public PlatformNeoForge getNeoForge() {
            return neoForge;
        }

        public void neoforge(Action<? super PlatformNeoForge> action) {
            action.execute(getNeoForge());
        }
    }
}
