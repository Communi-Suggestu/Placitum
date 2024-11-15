package com.communi.suggestu.placitum.core;

import com.communi.suggestu.placitum.platform.IPlatformProject;
import net.neoforged.gradle.dsl.common.extensions.subsystems.Subsystems;
import net.neoforged.gradle.vanilla.VanillaPlugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;

import java.util.Set;

public final class CorePlatformProject extends CommonPlatformProject implements IPlatformProject {

    public CorePlatformProject() {
        super();
    }

    @Override
    public void configure(Project project, Set<String> coreProjects) {
        super.configure(project, coreProjects);

        final Platform platform = project.getExtensions().getByType(Platform.class);

        project.getPlugins().apply(VanillaPlugin.class);

        project.getDependencies().addProvider(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, platform.getMinecraft().getVersion()
                        .map("net.minecraft:client:%s"::formatted));

        final Subsystems subsystems = project.getExtensions().getByType(Subsystems.class);
        subsystems.parchment(parchment -> {
            parchment.getMinecraftVersion().set(platform.getParchment().getMinecraftVersion());
            parchment.getMappingsVersion().set(platform.getParchment().getVersion());
        });
    }

    @Override
    protected Platform registerPlatformExtension(Project project) {
        return project.getExtensions().create(CorePlatformProject.Platform.class, CommonPlatformProject.Platform.EXTENSION_NAME, CorePlatformProject.Platform.class, project);
    }

    public abstract static class Platform extends CommonPlatformProject.Platform {

        protected Platform(Project project) {
            super(project);
        }
    }
}
