package com.communi.suggestu.placitum.core;

import com.communi.suggestu.placitum.platform.IPlatformProject;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public abstract class CommonPlatformProject implements IPlatformProject {

    public CommonPlatformProject() {
    }

    @Override
    public void configure(Project project, Set<String> coreProjects) {
        project.setGroup(project.getRootProject().getGroup());
        project.setVersion(project.getRootProject().getVersion());

        project.getPlugins().apply("java");
        project.getPlugins().apply("idea");
        project.getPlugins().apply("java-library");
        project.getPlugins().apply("maven-publish");

        project.getRepositories().maven(mavenConfig -> {
            mavenConfig.setUrl("https://ldtteam.jrog.io/ldtteam/minecraft");
            mavenConfig.setName("LDT Team Maven");
        });
        project.getRepositories().mavenCentral();
        project.getRepositories().mavenLocal();

        final String archivesBaseName = "%s-%s".formatted(project.getRootProject().getName(), project.getName());
        final String moduleName = archivesBaseName.replace("-", "_").toLowerCase(Locale.ROOT);
        
        final BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);
        base.getArchivesName().set(archivesBaseName);

        final Platform platform = registerPlatformExtension(project);
        
        final JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(platform.getJava().getVersion().map(JavaLanguageVersion::of));
        
        project.getTasks().named("jar", Jar.class, jar -> {
            jar.getArchiveBaseName().set(archivesBaseName);
            jar.getManifest().attributes(Map.of(
                    "Specification-Title", project.getRootProject().getName(),
                    "Specification-Vendor", platform.getProject().getOwner(),
                    "Specification-Version", project.getRootProject().getVersion(),
                    "Implementation-Title", project.getName(),
                    "Implementation-Vendor", platform.getProject().getOwner(),
                    "Implementation-Version", project.getVersion(),
                    "Automatic-Module-Name", moduleName,
                    "FMLModType", platform.getProject().getType().map(ProjectType::toModType)
            ));
            jar.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
        });

        //TODO: LDTTeam convention plugin

        final PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.getRepositories().maven(mavenRepo -> {
            mavenRepo.setUrl("file://" + project.getRootProject().file("repo").getAbsolutePath());
            mavenRepo.setName("ProjectLocal");
        });
    }

    protected abstract Platform registerPlatformExtension(Project project);

    protected final void disableCompiling(Project project) {
        var disabledTasks = List.of("build", "jar", "assemble", "compileJava", "compileTestJava", "test", "check");
        disabledTasks.forEach(taskName -> project.getTasks().named(taskName, task -> task.setEnabled(false)));
    }

    protected final Provider<String> getSupportedMinecraftVersionRange(Project project) {
        final Platform platform = project.getExtensions().getByType(Platform.class);

        return platform.getMinecraft().getVersion().zip(platform.getMinecraft().getAdditionalVersions(), (main, additional) -> {
            final List<ComparableVersion> versions = Stream.concat(Stream.of(main), additional.stream())
                    .map(ComparableVersion::new)
                    .toList();

            final ComparableVersion min = versions.stream().min(ComparableVersion::compareTo).orElseThrow();
            final ComparableVersion max = versions.stream().max(ComparableVersion::compareTo).orElseThrow();

            if (min.compareTo(max) == 0) {
                return "[%s]".formatted(min);
            } else {
                return "[%s,%s]".formatted(min, max);
            }
        });
    }


    public abstract static class Platform {

        public static final String EXTENSION_NAME = "platform";

        private final PlatformJava java;
        private final PlatformProject project;
        private final PlatformMinecraft minecraft;
        private final PlatformParchment parchment;

        @Inject
        protected Platform(final Project project) {
            this.java = project.getObjects().newInstance(PlatformJava.class, project);
            this.project = project.getObjects().newInstance(PlatformProject.class, project);
            this.minecraft = project.getObjects().newInstance(PlatformMinecraft.class, project);
            this.parchment = project.getObjects().newInstance(PlatformParchment.class, project, minecraft);
        }

        public abstract static class PlatformJava {
            @Inject
            public PlatformJava(Project project) {
                getVersion().convention(project.getProviders().gradleProperty("java.version").map(String::trim).map(Integer::parseInt));
            }

            @Input
            abstract Property<Integer> getVersion();
        }

        public abstract static class PlatformProject {

            @Inject
            public PlatformProject(final Project project) {
                getType().convention(project.getProviders().gradleProperty("project.type").map(String::trim).map(ProjectType::valueOf).orElse(ProjectType.LIBRARY));
                getOwner().convention(project.getProviders().gradleProperty("project.owner").map(String::trim));
            }

            @Input
            abstract Property<ProjectType> getType();

            @Input
            abstract Property<String> getOwner();
        }

        public abstract static class PlatformMinecraft {

            @Inject
            public PlatformMinecraft(Project project) {
                getVersion().convention(project.getProviders().gradleProperty("minecraft.version").map(String::trim));
                getAdditionalVersions().convention(project.getProviders().gradleProperty("minecraft.additionalVersions").map(String::trim).map(s -> List.of(s.split(","))).orElse(List.of()));
            }

            @Input
            abstract Property<String> getVersion();

            @Input
            abstract ListProperty<String> getAdditionalVersions();
        }

        public abstract static class PlatformParchment {
            @Inject
            public PlatformParchment(Project project, PlatformMinecraft minecraft) {
                getVersion().convention(project.getProviders().gradleProperty("parchment.version").map(String::trim));
                getMinecraftVersion().convention(project.getProviders().gradleProperty("parchment.minecraftVersion").map(String::trim).orElse(minecraft.getVersion()));
            }

            @Input
            abstract Property<String> getVersion();

            @Input
            abstract Property<String> getMinecraftVersion();
        }


        @Nested
        public PlatformJava getJava() {
            return java;
        }

        public void java(Action<? super PlatformJava> action) {
            action.execute(getJava());
        }

        @Nested
        public PlatformProject getProject() {
            return project;
        }

        public void project(Action<? super PlatformProject> action) {
            action.execute(getProject());
        }

        @Nested
        public PlatformMinecraft getMinecraft() {
            return minecraft;
        }

        public void minecraft(Action<? super PlatformMinecraft> action) {
            action.execute(getMinecraft());
        }

        public PlatformParchment getParchment() {
            return parchment;
        }

        public void parchment(Action<? super PlatformParchment> action) {
            action.execute(getParchment());
        }
    }

    public enum ProjectType {
        LIBRARY,
        MOD;

        public String toModType() {
            return switch (this) {
                case LIBRARY -> "GAMELIBRARY";
                case MOD -> "MOD";
            };
        }
    }
}
