package com.communi.suggestu.placitum.core;

import com.communi.suggestu.placitum.platform.IPlatformProject;
import com.communi.suggestu.placitum.util.ValueCallable;
import net.neoforged.gradle.dsl.common.extensions.AccessTransformers;
import net.neoforged.gradle.dsl.common.extensions.Minecraft;
import net.neoforged.gradle.dsl.common.extensions.sourceset.RunnableSourceSet;
import net.neoforged.gradle.dsl.common.extensions.subsystems.Subsystems;
import net.neoforged.gradle.vanilla.VanillaPlugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.SourceSetContainer;

import javax.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class CommonPlatformProject extends AbstractPlatformProject implements IPlatformProject {

    public CommonPlatformProject() {
        super();
    }

    @Override
    public void configure(Project project, String coreProjectPath, Set<String> commonProjectPaths, AbstractPlatformProject.Platform defaults) {
        super.configure(project, coreProjectPath, commonProjectPaths, defaults);

        final Platform platform = project.getExtensions().getByType(Platform.class);

        project.getPlugins().apply(VanillaPlugin.class);

        final Set<Project> commonProjects =
                commonProjectPaths.stream()
                .map(project::project)
                .collect(Collectors.toSet());

        if (commonProjects.contains(project)) {
            commonProjects.clear();
        }

        for (Project commonProject : commonProjects) {
            final Dependency commonProjectDependency = project.getDependencies().create(commonProject);
            excludeMinecraftDependencies(commonProjectDependency);

            final Configuration apiConfiguration = project.getConfigurations().getByName(JavaPlugin.API_CONFIGURATION_NAME);
            apiConfiguration.getDependencies().add(commonProjectDependency);
        }

        project.getDependencies().addProvider(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, platform.getMinecraft().getVersion()
                        .map("net.minecraft:neoform_client:%s"::formatted));

        final Subsystems subsystems = project.getExtensions().getByType(Subsystems.class);
        subsystems.parchment(parchment -> {
            parchment.getMinecraftVersion().set(platform.getParchment().getMinecraftVersion());
            parchment.getMappingsVersion().set(platform.getParchment().getVersion());
        });

        final AccessTransformers accessTransformers = project.getExtensions().getByType(Minecraft.class).getAccessTransformers();
        accessTransformers.getFiles().from(platform.getAccessTransformers());

        final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.configureEach(sourceSet -> {
            final RunnableSourceSet runSourceSet = sourceSet.getExtensions().getByType(RunnableSourceSet.class);
            runSourceSet.getModIdentifier().set(project.getRootProject().getName().toLowerCase());
        });
    }

    @Override
    protected Platform registerPlatformExtension(Project project, AbstractPlatformProject.Platform defaults) {
        return project.getExtensions().create(CommonPlatformProject.Platform.class, AbstractPlatformProject.Platform.EXTENSION_NAME, CommonPlatformProject.Platform.class, project, defaults);
    }

    @Override
    protected Provider<String> getLoaderVersion(AbstractPlatformProject.Platform platform) {
        return platform.getMinecraft().getVersion();
    }

    @Override
    protected Map<String, ?> getInterpolatedProperties(AbstractPlatformProject.Platform platform) {
        return Map.of();
    }

    @Override
    protected Set<Configuration> getDependencyInterpolationConfigurations(Project project) {
        return Set.of();
    }

    public abstract static class Platform extends AbstractPlatformProject.Platform {

        @Inject
        public Platform(Project project, final AbstractPlatformProject.Platform settings) {
            super(project, settings);
        }

        @InputFiles
        public abstract ConfigurableFileCollection getAccessTransformers();
    }
}
