package com.communi.suggestu.placitum.core;

import com.communi.suggestu.placitum.platform.IPlatformProject;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
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
import org.gradle.language.jvm.tasks.ProcessResources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class CommonPlatformProject implements IPlatformProject {

    public CommonPlatformProject() {
    }

    static void excludeMinecraftDependencies(ExternalModuleDependency dependency) {
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

    @Override
    public void configure(Project project, String coreCodeProject, Set<String> commonProjects) {
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

        final PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.getRepositories().maven(mavenRepo -> {
            mavenRepo.setUrl("file://" + project.getRootProject().file("repo").getAbsolutePath());
            mavenRepo.setName("ProjectLocal");
        });

        project.getTasks().named("processResources", ProcessResources.class, task -> {
            final File resourcesDir = new File(project.getProjectDir(), "src/main/resources");
            final File mainDir = resourcesDir.getParentFile();
            final File templatesDir = new File(mainDir, "templates");

            task.from(templatesDir, copy -> {
                final Map<String, Object> interpolate = new HashMap<>(Map.of(
                        "version", project.getVersion().toString(),
                        "name", project.getRootProject().getName(),
                        "package", "%s.%s".formatted(project.getRootProject().getGroup(), project.getName().toLowerCase(Locale.ROOT)),
                        "minecraftVersion", platform.getParchment().getMinecraftVersion(),
                        "loaderVersion", getLoaderVersion(platform)
                ));

                interpolate.putAll(getInterpolatedProperties(platform));
                interpolate.putAll(project.getRootProject().getProperties());
                interpolate.putAll(project.getProperties());

                final Configuration runtimeClasspath = project.getConfigurations().getByName("runtimeClasspath");
                final Multimap<String, ExternalDependency> dependenciesByName = runtimeClasspath.getAllDependencies()
                        .stream()
                        .filter(ExternalDependency.class::isInstance)
                        .map(ExternalDependency.class::cast)
                        .collect(Multimaps.toMultimap(ExternalDependency::getName, d -> d, HashMultimap::create));

                dependenciesByName.asMap().forEach((name, dependencies) -> {
                    convertToNotations(name, dependencies).forEach(entry -> interpolate.put(entry.getKey(), entry.getValue()));
                });

                task.getInputs().properties(interpolate);

                copy.expand(interpolate);
            });
        });
    }

    private static Stream<Map.Entry<String, String>> convertToNotations(String moduleName, Collection<ExternalDependency> dependencies) {
        if (dependencies.size() == 1) {
            return convertToNotations(adaptModuleName(moduleName), dependencies.iterator().next());
        }

        return dependencies.stream()
                .flatMap(dependency -> convertToNotations(getFullModuleName(dependency), dependency));
    }

    private static String adaptModuleName(String name) {
        final String[] parts = name.split("-");
        return Arrays.stream(parts).reduce((s, s2) -> s + StringUtils.capitalize(s2)).orElseThrow();
    }

    private static String getFullModuleName(ExternalDependency dependency) {
        String group = dependency.getGroup();
        if (group != null) {
            group = group.toLowerCase(Locale.ROOT);
            final String[] sections = group.split("\\.");
            if (sections.length > 1) {
                group = Arrays.stream(sections).reduce((s, s2) -> s + StringUtils.capitalize(s2)).orElseThrow();
            }
        }

        return group + StringUtils.capitalize(adaptModuleName(dependency.getName()));
    }

    private static Stream<Map.Entry<String, String>> convertToNotations(String name, ExternalDependency dependency) {
        return Stream.of(
                Map.entry(
                        "supported%sNpmVersionRange".formatted(StringUtils.capitalize(name)),
                        createSupportedVersionRange(dependency, true)
                ),
                Map.entry(
                        "supported%sMavenVersionRange".formatted(StringUtils.capitalize(name)),
                        createSupportedVersionRange(dependency, false)
                )
        );
    }

    protected abstract Platform registerPlatformExtension(Project project);

    protected abstract Provider<String> getLoaderVersion(Platform platform);

    protected abstract Map<String, ?> getInterpolatedProperties(Platform platform);

    protected final void disableCompiling(Project project) {
        var disabledTasks = List.of("build", "jar", "assemble", "compileJava", "compileTestJava", "test", "check");
        disabledTasks.forEach(taskName -> project.getTasks().named(taskName, task -> task.setEnabled(false)));
    }

    protected final Provider<String> getSupportedMinecraftVersionRange(Project project, boolean npmCompatible) {
        final Platform platform = project.getExtensions().getByType(Platform.class);

        return platform.getMinecraft().getVersion().zip(platform.getMinecraft().getAdditionalVersions(), (main, additional) -> {
            final List<ComparableVersion> versions = Stream.concat(Stream.of(main), additional.stream())
                    .map(ComparableVersion::new)
                    .toList();

            return createSupportedVersionRange(npmCompatible, versions);
        });
    }

    protected final boolean isRunningWithIdea(Project project) {
        final File DotIdeaDirectory = new File(project.getRootProject().getProjectDir(), ".idea");
        final File GradleXml = new File(DotIdeaDirectory, "gradle.xml");
        try {
            return new String(Files.readAllBytes(GradleXml.toPath())).contains("<option name=\"delegatedBuild\" value=\"false\" />");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static @NotNull String createSupportedVersionRange(ExternalDependency dependency, boolean npmCompatible) {
        final VersionConstraint constraint = dependency.getVersionConstraint();

        if (dependency.getVersion() != null)
            return createSupportedVersionRange(dependency.getVersion(), npmCompatible);

        if (!constraint.getRequiredVersion().isBlank())
            return createSupportedVersionRange(constraint.getRequiredVersion(), npmCompatible);

        if (!constraint.getStrictVersion().isBlank())
            return createSupportedVersionRange(constraint.getStrictVersion(), npmCompatible);

        if (!constraint.getPreferredVersion().isBlank())
            return createSupportedVersionRange(constraint.getPreferredVersion(), npmCompatible);

        throw new InvalidUserDataException("Dependency does not have a version constraint or version specified");
    }

    private static final Pattern VERSION_RANGE_PATTERN = Pattern.compile("(?<range>([(\\[])(?<min>[0-9a-zA-Z.\\-]+)(, ?(?<max>[0-9a-zA-Z.\\-]*))?)(?<closer>[)\\]])");

    protected static String createSupportedVersionRange(String versionRange, boolean npmCompatible) {
        List<String> allRanges = new ArrayList<>();
        Matcher m = VERSION_RANGE_PATTERN.matcher(versionRange);
        while (m.find()) {
            allRanges.add(m.group());
        }

        if (allRanges.size() > 1) {
            throw new InvalidUserDataException("Multiple none continuous version ranges are not supported");
        }

        return getBounds(versionRange).toRange(npmCompatible);
    }

    private record Bounds(String min, @Nullable String max, boolean maxInclusive) {

        private String toRange(boolean npmCompatible) {
            if (max == null || max.isBlank()) {
                if (maxInclusive)
                    throw new InvalidUserDataException("Dependency bound without maximum is specified as having a maximum that is inclusive");

                return npmCompatible ? ">=%s".formatted(min) : "[%s,)".formatted(min);
            }

            if (min.equals(max)) {
                return npmCompatible ? "=%s".formatted(min) : "[%s]".formatted(min);
            }

            if (!maxInclusive) {
                return npmCompatible ? ">=%s <%s".formatted(min, max) : "[%s,%s)".formatted(min, max);
            }

            return npmCompatible ? ">=%s <=%s".formatted(min, max) : "[%s,%s]".formatted(min, max);
        }
    }

    private static Bounds getBounds(String version) {
        if (version.endsWith(".+")) {
            return getPlusBounds(version);
        }

        if (!version.contains(",")
                && !version.contains("[")
                && !version.contains("]")
                && !version.contains("(")
                && !version.contains(")")) {
            return new Bounds(version, version, true);
        }

        final Matcher matcher = VERSION_RANGE_PATTERN.matcher(version);
        if (!matcher.matches()) {
            throw new InvalidUserDataException("Invalid version range: %s".formatted(version));
        }

        final String min = matcher.group("min");
        final String max = matcher.group("max");
        final String closer = matcher.group("closer");
        final boolean isInclusive = closer.equals("]");

        return new Bounds(min, max, isInclusive);
    }

    private static Bounds getPlusBounds(String version) {
        //This version ends with .+, we need to remove it to get the lower bound
        //Then extract the last section and increment it by one to get the max
        //The result is then a max exclusive range.
        final String min = version.substring(0, version.length() - 2);

        final String lastSection = min.substring(min.lastIndexOf('.') + 1);
        final int nextLastSection = Integer.parseInt(lastSection) + 1;

        final String max = min.substring(0, min.lastIndexOf('.') + 1) + nextLastSection;

        return new Bounds(min, max, false);
    }

    private static @NotNull String createSupportedVersionRange(boolean npmCompatible, List<ComparableVersion> versions) {
        if (!npmCompatible) {
            return versions.stream()
                    .map(ComparableVersion::toString)
                    .map("[%s]"::formatted)
                    .collect(Collectors.joining(","));
        }

        //This format supports all available versions directly.
        return versions.stream()
                .map(ComparableVersion::toString)
                .map("=%s"::formatted)
                .collect(Collectors.joining(" "));
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
