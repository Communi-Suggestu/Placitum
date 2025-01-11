package com.communi.suggestu.placitum;

import com.communi.suggestu.placitum.core.CommonPlatformProject;
import com.communi.suggestu.placitum.platform.IPlatformProject;
import com.communi.suggestu.placitum.platform.SettingsPlatformExtension;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;
import java.util.function.Supplier;

public class SettingsPlugin implements Plugin<Settings> {
    @Override
    public void apply(@NotNull Settings target) {
        final SettingsPlatformExtension extension = target.getExtensions().create(SettingsPlatformExtension.EXTENSION_NAME, SettingsPlatformExtension.class, target);

        target.getPlugins().apply("org.gradle.toolchains.foojay-resolver-convention");

        target.getGradle().beforeProject(new DynamicProjectPluginAdapter(target, extension.getDefaults()));

        target.pluginManagement(spec -> {
            spec.repositories(repositories -> {
                repositories.gradlePluginPortal();
                repositories.maven(mavenConfig -> {
                    mavenConfig.setUrl("https://maven.neoforged.net/releases");
                    mavenConfig.setName("NeoForged");
                });
                repositories.maven(mavenConfig -> {
                    mavenConfig.setUrl("https://maven.fabricmc.net/");
                    mavenConfig.setName("FabricMC");
                });
                repositories.mavenLocal();
                repositories.mavenCentral();
            });
        });
    }

    private record DynamicProjectPluginAdapter(Settings settings, CommonPlatformProject.Platform defaults) implements Action<Project> {
        @Override
        public void execute(@NotNull Project project) {
            final SettingsPlatformExtension projectManagementExtension = settings.getExtensions().getByType(SettingsPlatformExtension.class);

            final Function<Project, IPlatformProject> builder = projectManagementExtension.findProject(project.getPath());
            if (builder != null) {
                final IPlatformProject platformProject = builder.apply(project);
                platformProject.configure(
                        project,
                        projectManagementExtension.getCoreProjectPath(),
                        projectManagementExtension.getCommonProjectPaths(),
                        defaults
                );
            }
        }
    }
}
