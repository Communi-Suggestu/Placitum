package com.communi.suggestu.placitum.core;

import com.communi.suggestu.placitum.platform.IPlatformProject;
import com.communi.suggestu.placitum.util.ValueCallable;
import net.neoforged.gradle.dsl.common.extensions.AccessTransformers;
import net.neoforged.gradle.dsl.common.extensions.JarJar;
import net.neoforged.gradle.dsl.common.extensions.Minecraft;
import net.neoforged.gradle.dsl.common.extensions.subsystems.Subsystems;
import net.neoforged.gradle.vanilla.VanillaPlugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFiles;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class CorePlatformProject extends CommonPlatformProject implements IPlatformProject {

    public CorePlatformProject() {
        super();
    }

    @Override
    public void configure(Project project, String coreProjectPath, Set<String> commonProjectPaths) {
        super.configure(project, coreProjectPath, commonProjectPaths);

        final Platform platform = project.getExtensions().getByType(Platform.class);

        project.getPlugins().apply(VanillaPlugin.class);

        final Set<Project> commonProjects =
                commonProjectPaths.stream()
                .map(project::project)
                .collect(Collectors.toSet());

        if (commonProjects.contains(project)) {
            commonProjects.clear();
        }

        final JarJar jarJar = project.getExtensions().getByType(JarJar.class);
        if (!commonProjects.isEmpty()) {
            jarJar.enable();
        }

        for (Project commonProject : commonProjects) {
            final Provider<Dependency> coreProjectProvider = commonProject.provider(new ValueCallable<>(commonProject))
                    .map(project.getDependencies()::create);

            project.getDependencies().addProvider(JavaPlugin.API_CONFIGURATION_NAME, coreProjectProvider, CommonPlatformProject::excludeMinecraftDependencies);

            project.getDependencies().addProvider(JarJar.EXTENSION_NAME, coreProjectProvider, dependency -> {
                jarJar.ranged(dependency, "[%s]".formatted(commonProject.getVersion()));
                jarJar.pin(dependency, commonProject.getVersion().toString());

                excludeMinecraftDependencies(dependency);
            });
        }

        project.getDependencies().addProvider(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, platform.getMinecraft().getVersion()
                        .map("net.minecraft:client:%s"::formatted));

        final Subsystems subsystems = project.getExtensions().getByType(Subsystems.class);
        subsystems.parchment(parchment -> {
            parchment.getMinecraftVersion().set(platform.getParchment().getMinecraftVersion());
            parchment.getMappingsVersion().set(platform.getParchment().getVersion());
        });

        final AccessTransformers accessTransformers = project.getExtensions().getByType(Minecraft.class).getAccessTransformers();
        accessTransformers.getFiles().from(platform.getAccessTransformers());
    }

    @Override
    protected Platform registerPlatformExtension(Project project) {
        return project.getExtensions().create(CorePlatformProject.Platform.class, CommonPlatformProject.Platform.EXTENSION_NAME, CorePlatformProject.Platform.class, project);
    }

    @Override
    protected Provider<String> getLoaderVersion(CommonPlatformProject.Platform platform) {
        return platform.getMinecraft().getVersion();
    }

    @Override
    protected Map<String, ?> getInterpolatedProperties(CommonPlatformProject.Platform platform) {
        return Map.of();
    }

    public abstract static class Platform extends CommonPlatformProject.Platform {

        protected Platform(Project project) {
            super(project);
        }

        @InputFiles
        public abstract ConfigurableFileCollection getAccessTransformers();
    }
}
