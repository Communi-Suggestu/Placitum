package com.communi.suggestu.placitum.core;

import com.communi.suggestu.placitum.platform.IPlatformProject;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.codehaus.groovy.tools.StringHelper;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.problems.ProblemGroup;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.SourceSetContainer;
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

public abstract class AbstractPlatformProject implements IPlatformProject {

    @SuppressWarnings("UnstableApiUsage")
    protected static ProblemGroup PLACITUM_GROUP = ProblemGroup.create("placitum", "Placitum");

    public AbstractPlatformProject() {
    }

    static void excludeMinecraftDependencies(Dependency dependency) {
        if (!(dependency instanceof ModuleDependency module)) {
            return;
        }

        module.exclude(Map.of(
                "group", "net.minecraft",
                "module", "client"
        ));
        module.exclude(Map.of(
                "group", "net.minecraft",
                "module", "server"
        ));
        module.exclude(Map.of(
                "group", "net.minecraft",
                "module", "joined"
        ));
    }

    @Override
    public void configure(Project project, String coreCodeProject, Set<String> commonProjects, Platform defaults) {
        project.setGroup(project.getRootProject().getGroup());
        project.setVersion(project.getRootProject().getVersion());

        project.getPlugins().apply("java");
        project.getPlugins().apply("idea");
        project.getPlugins().apply("java-library");
        project.getPlugins().apply("maven-publish");

        project.getRepositories().maven(mavenConfig -> {
            mavenConfig.setUrl("https://ldtteam.jfrog.io/ldtteam/modding");
            mavenConfig.setName("LDT Team Maven");
        });
        project.getRepositories().mavenCentral();
        project.getRepositories().mavenLocal();

        final String archivesBaseName = "%s-%s".formatted(project.getRootProject().getName(), project.getName());
        final String moduleName = archivesBaseName.replace("-", "_").toLowerCase(Locale.ROOT);

        final BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);
        base.getArchivesName().set(archivesBaseName);

        final Platform platform = registerPlatformExtension(project, defaults);

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
            final Map<String, Object> interpolate = new HashMap<>(Map.of(
                    "version", project.getVersion().toString(),
                    "name", project.getRootProject().getName(),
                    "project", new HashMap<>(Map.of(
                            "package", "%s.%s".formatted(project.getRootProject().getGroup(), project.getName().toLowerCase(Locale.ROOT))
                    )),
                    "minecraft", new HashMap<>(Map.of(
                            "version", platform.getMinecraft().getVersion(),
                            "range", new HashMap<>(Map.of(
                                    "neoforge",  getSupportedMinecraftVersionRange(project, false),
                                    "fabric", getSupportedMinecraftVersionRange(project, true)
                            ))
                    )),
                    "loader", new HashMap<>(Map.of(
                            "version", getLoaderVersion(platform)
                    )),
                    "java", new HashMap<>(Map.of(
                            "version", platform.getJava().getVersion(),
                            "range", new HashMap<>(Map.of(
                                    "neoforge", platform.getJava().getVersion().map("[%s,)"::formatted),
                                    "fabric", platform.getJava().getVersion().map("=%s"::formatted)
                            ))
                    ))
            ));

            processPropertiesMap(interpolate, getInterpolatedProperties(platform));

            final Map<String, Object> rootProjectInterpolation = new HashMap<>();
            processPropertiesMap(rootProjectInterpolation, project.getRootProject().getProperties());

            final Map<String, Object> projectInterpolation = new HashMap<>();
            processPropertiesMap(projectInterpolation, project.getProperties());

            final Map<String, Object> projectProperties = new HashMap<>();
            projectProperties.put("project.root", rootProjectInterpolation);
            projectProperties.put("project", projectInterpolation);
            processPropertiesMap(interpolate, projectProperties);

            final Multimap<String, ExternalDependency> dependenciesByName = HashMultimap.create();
            getDependencyInterpolationConfigurations(project).forEach(configuration -> {
                configuration.getAllDependencies()
                        .stream()
                        .filter(ExternalDependency.class::isInstance)
                        .map(ExternalDependency.class::cast)
                        .forEach(dependency -> dependenciesByName.put(dependency.getName(), dependency));
            });
            registerAdditionalDependencies(project, platform, dependenciesByName);

            dependenciesByName.asMap().forEach((name, dependencies) -> {
                final Map<String, ?> dependencyInterpolations = convertToNotations(name, dependencies)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                processPropertiesMap(interpolate, dependencyInterpolations);
            });

            task.getInputs().properties(interpolate);

            final List<String> notMatchingFiles = new ArrayList<>();
            notMatchingFiles.add("**/*.cfg");
            notMatchingFiles.add("**/*.accesswidener");
            notMatchingFiles.add("**/*.jar");
            notMatchingFiles.add("**/*.png");
            notMatchingFiles.add("**/*.jpg");
            notMatchingFiles.add("**/*.gif");
            notMatchingFiles.add("**/*.ico");
            notMatchingFiles.add("**/*.svg");
            notMatchingFiles.add("**/lang/**");
            task.filesNotMatching(notMatchingFiles, spec -> {
                spec.expand(interpolate);
            });
        });

        final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);

        sourceSets.configureEach(sourceSet -> {
            project.getDependencies().addProvider(
                    sourceSet.getImplementationConfigurationName(),
                    platform.getDefaults().getJetbrainsAnnotationsVersion()
                            .map("org.jetbrains:annotations:%s"::formatted)
                            .map(project.getDependencies()::create)
            );
        });
    }

    record PropertyMapEntry(String key, Object value) {
    }

    @SuppressWarnings("unchecked")
    private static void processPropertiesMap(final Map<String, Object> result, final Map<String, ?> input) {
        final Multimap<String, PropertyMapEntry> keyPrefixedInputMap = HashMultimap.create();
        input.forEach((key, value) -> {
            if (key.equals("properties")) {
                //Don't recurse down project properties, gets into a SOE
                return;
            }
            if (!key.contains(".") && !key.toLowerCase(Locale.ROOT).equals(key)) {
                //We have a camel case string, turn it into a snake case string
                key = key.replaceAll("([a-z])([A-Z])", "$1.$2").toLowerCase(Locale.ROOT);
            }
            final String[] keyParts = key.split("\\.");
            if (keyParts.length == 1) {
                keyPrefixedInputMap.put(keyParts[0], new PropertyMapEntry("", value));
            } else {
                final String newKey = String.join(".", Arrays.copyOfRange(keyParts, 1, keyParts.length));
                keyPrefixedInputMap.put(keyParts[0], new PropertyMapEntry(newKey, value));
            }
        });

        keyPrefixedInputMap.asMap().forEach((rootKey, values) -> {
            //Check if we have an overlap within the root key
            if (values.stream().anyMatch(v -> v.key().isBlank()) &&
                    values.stream().anyMatch(v -> !v.key().isBlank())) {
                final PropertyMapEntry rootValue = values.stream().filter(v -> v.key().isBlank()).findFirst().orElseThrow();
                values.remove(rootValue);
                values.add(new PropertyMapEntry("_", rootValue.value()));
            }

            if (values.stream().anyMatch(v -> v.key().isBlank())) {
                final Object value = values.iterator().next().value();
                //Assume providers are safe, but you will never really know without resolving them.
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    result.put(rootKey, value);
                } else if (value instanceof Provider<?> provider) {
                    result.put(rootKey, provider.map(Object::toString));
                } else if (value instanceof Map<?,?> map) {
                    try {
                        final Map<String, Object> newResultMap = new HashMap<>();
                        processPropertiesMap(newResultMap, (Map<String, ?>) map);
                        if (newResultMap.isEmpty()) {
                            return;
                        }
                        if (!result.containsKey(rootKey)) {
                            result.put(rootKey, newResultMap);
                        } else if (result.get(rootKey) instanceof Map) {
                            MergeMaps((Map<String, Object>) result.get(rootKey), newResultMap);
                        } else if (newResultMap.size() == 1 && newResultMap.containsKey("_")) {
                            result.put(rootKey, newResultMap.get("_"));
                        } else {
                            throw new InvalidUserDataException("Cannot merge a map with a non-map value");
                        }
                    } catch (InvalidUserDataException | ClassCastException ignored) {
                    }
                }
            } else {
                final Map<String, ?> newInputMap = values.stream()
                        .filter(v -> v.value() != null)
                        .collect(Collectors.toMap(PropertyMapEntry::key, PropertyMapEntry::value));
                final Map<String, Object> newResultMap = new HashMap<>();
                processPropertiesMap(newResultMap, newInputMap);
                if (newResultMap.isEmpty()) {
                    return;
                }

                if (newResultMap.containsKey("_") && newResultMap.get("_") instanceof Map<?,?> map) {
                    newResultMap.remove("_");
                    if (result.containsKey(rootKey) && result.get(rootKey) instanceof Map) {
                        MergeMaps((Map<String, Object>) result.get(rootKey), (Map<String, Object>) map);
                    } else if (!result.containsKey(rootKey)) {
                        result.put(rootKey, map);
                    } else {
                        throw new InvalidUserDataException("Cannot merge a map with a non-map value");
                    }
                }

                if (!result.containsKey(rootKey)) {
                    result.put(rootKey, newResultMap);
                } else if (result.get(rootKey) instanceof Map) {
                    MergeMaps((Map<String, Object>) result.get(rootKey), newResultMap);
                } else if (newResultMap.size() == 1 && newResultMap.containsKey("_")) {
                    result.put(rootKey, newResultMap.get("_"));
                } else {
                    throw new InvalidUserDataException("Cannot merge a map with a non-map value");
                }
            }
        });

        final Set<String> emptyOrNullKeys = result.keySet().stream()
                .filter(key -> result.get(key) == null || (result.get(key) instanceof Map && ((Map<?, ?>) result.get(key)).isEmpty()))
                .filter(key -> result.get(key) instanceof String ||
                        result.get(key) instanceof Number ||
                        result.get(key) instanceof Boolean ||
                        result.get(key) instanceof Provider<?>)
                .collect(Collectors.toSet());

        emptyOrNullKeys.forEach(result::remove);
    }

    @SuppressWarnings("unchecked")
    private static void MergeMaps(final Map<String, Object> left, final Map<String, Object> right) {
        right.forEach((key, value) -> {
            if (left.containsKey(key)) {
                final Object leftValue = left.get(key);
                if (value instanceof Map && leftValue instanceof Map) {
                    MergeMaps((Map<String, Object>) leftValue, (Map<String, Object>) value);
                } else {
                    left.put(key, value);
                }
            } else {
                left.put(key, value);
            }
        });
    }

    private static Stream<Map.Entry<String, String>> convertToNotations(String moduleName, Collection<ExternalDependency> dependencies) {
        return dependencies.stream()
                .flatMap(dependency -> convertToNotations(getFullModuleName(dependency), dependency))
                .distinct();
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

        return (group + StringUtils.capitalize(adaptModuleName(dependency.getName()))).replace("-", "_");
    }

    private static Stream<Map.Entry<String, String>> convertToNotations(String name, ExternalDependency dependency) {
        return Stream.of(
                Map.entry(
                        "dependencies%sNpm".formatted(StringUtils.capitalize(name)),
                        createSupportedVersionRange(dependency, true)
                ),
                Map.entry(
                        "dependencies%sMaven".formatted(StringUtils.capitalize(name)),
                        createSupportedVersionRange(dependency, false)
                )
        );
    }

    protected abstract Platform registerPlatformExtension(Project project, Platform defaults);

    protected abstract Provider<String> getLoaderVersion(Platform platform);

    protected abstract Map<String, ?> getInterpolatedProperties(Platform platform);

    protected void registerAdditionalDependencies(Project project, Platform platform, Multimap<String, ExternalDependency> byNameDependencies) {};

    protected abstract Set<Configuration> getDependencyInterpolationConfigurations(Project project);

    protected final Provider<String> getSupportedMinecraftVersionRange(Project project, boolean npmCompatible) {
        final Platform platform = project.getExtensions().getByType(Platform.class);

        return platform.getMinecraft().getVersion().zip(platform.getMinecraft().getAdditionalVersions(), (main, additional) -> {
            final List<ComparableVersion> versions = Stream.concat(Stream.of(main), additional.stream())
                    .filter(s -> !s.isBlank())
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
        if (versionRange.equals("+")) {
            if (npmCompatible) {
                return "*";
            }

            return "+"; //Wildcard matches against anything recommending the + version.
        }

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
                .map("\"=%s\""::formatted)
                .collect(Collectors.joining(", "));
    }

    /**
     * The core platform.
     */
    public abstract static class Platform {

        public static final String EXTENSION_NAME = "platform";

        private final PlatformJava java;
        private final PlatformProject project;
        private final PlatformMinecraft minecraft;
        private final PlatformParchment parchment;
        private final PlatformDefaults defaults;

        protected Platform(final ObjectFactory objects, final ProviderFactory providers) {
            this.java = objects.newInstance(PlatformJava.class, objects, providers);
            this.project = objects.newInstance(PlatformProject.class, objects, providers);
            this.minecraft = objects.newInstance(PlatformMinecraft.class, objects, providers);
            this.parchment = objects.newInstance(PlatformParchment.class, objects, providers, minecraft);
            this.defaults = objects.newInstance(PlatformDefaults.class, objects, providers);
        }

        protected Platform(final Project project, final Platform settings) {
            this(project.getObjects(), project.getProviders());

            this.java.from(project.getObjects(), project.getProviders(), settings.java);
            this.project.from(project.getObjects(), project.getProviders(), settings.project);
            this.minecraft.from(project.getObjects(), project.getProviders(), settings.minecraft);
            this.parchment.from(project.getObjects(), project.getProviders(), settings.parchment, this.minecraft);
            this.defaults.from(project.getObjects(), project.getProviders(), settings.defaults);
        }

        public abstract static class PlatformJava {
            @Inject
            public PlatformJava(final ObjectFactory objects, final ProviderFactory providers) {
                getVersion().convention(getDefaultJavaVersion(providers));
            }

            private static @NotNull Provider<Integer> getDefaultJavaVersion(ProviderFactory providers) {
                return providers.gradleProperty("java.version").map(String::trim).map(Integer::parseInt);
            }

            @Input
            abstract Property<Integer> getVersion();

            private void from(ObjectFactory objects, ProviderFactory providers, PlatformJava java) {
                getVersion().set(
                        java.getVersion().orElse(getDefaultJavaVersion(providers))
                );
            }
        }

        public abstract static class PlatformProject {

            @Inject
            public PlatformProject(final ObjectFactory objects, final ProviderFactory providers) {
                getType().convention(getDefaultProjectType(providers));
                getOwner().convention(getDefaultProjectOwner(providers));
                getModId().convention(getDefaultProjectModId(providers));
            }

            private static @NotNull Provider<String> getDefaultProjectOwner(ProviderFactory providers) {
                return providers.gradleProperty("project.owner").map(String::trim);
            }

            private static @NotNull Provider<String> getDefaultProjectModId(ProviderFactory providers) {
                return providers.gradleProperty("project.mod.id").map(String::trim);
            }

            private static @NotNull Provider<ProjectType> getDefaultProjectType(ProviderFactory providers) {
                return providers.gradleProperty("project.type").map(String::trim).map(ProjectType::valueOf).orElse(ProjectType.MOD);
            }

            public void library() {
                getType().set(ProjectType.LIBRARY);
            }

            @Input
            abstract Property<ProjectType> getType();

            @Input
            abstract Property<String> getOwner();

            @Input
            abstract Property<String> getModId();

            private void from(ObjectFactory objects, ProviderFactory providers, PlatformProject project) {
                this.getOwner().set(
                        project.getOwner().orElse(getDefaultProjectOwner(providers))
                );
                this.getType().set(
                        project.getType().orElse(getDefaultProjectType(providers))
                );
                this.getModId().set(
                        project.getModId().orElse(getDefaultProjectModId(providers))
                );
            }
        }

        public abstract static class PlatformMinecraft {

            @Inject
            public PlatformMinecraft(final ObjectFactory objects, final ProviderFactory providers) {
                getVersion().convention(getDefaultVersion(providers));
                getAdditionalVersions().convention(getDefaultAdditionalVersions(providers));
            }

            private static @NotNull Provider<List<String>> getDefaultAdditionalVersions(ProviderFactory providers) {
                return providers.gradleProperty("minecraft.additionalVersions").map(String::trim).map(s -> {
                    final String[]  split = s.split(",");
                    final List<String> result = new ArrayList<>();
                    Collections.addAll(result, split);
                    return result;
                }).orElse(new ArrayList<>());
            }

            private static @NotNull Provider<String> getDefaultVersion(ProviderFactory providers) {
                return providers.gradleProperty("minecraft.version").map(String::trim);
            }

            @Input
            abstract Property<String> getVersion();

            @Input
            abstract ListProperty<String> getAdditionalVersions();

            private void from(ObjectFactory objects, ProviderFactory providers, PlatformMinecraft minecraft) {
                getVersion().set(
                        minecraft.getVersion().orElse(getDefaultVersion(providers))
                );
                getAdditionalVersions().set(
                        minecraft.getAdditionalVersions().orElse(getDefaultAdditionalVersions(providers))
                );
            }
        }

        public abstract static class PlatformParchment {
            @Inject
            public PlatformParchment(final ObjectFactory objects, final ProviderFactory providers, PlatformMinecraft minecraft) {
                getVersion().convention(getDefaultVersion(providers));
                getMinecraftVersion().convention(getDefaultMinecraftVersion(providers, minecraft));
            }

            private @NotNull Provider<String> getDefaultMinecraftVersion(ProviderFactory providers, PlatformMinecraft minecraft) {
                return providers.gradleProperty("parchment.minecraftVersion").map(String::trim).orElse(minecraft.getVersion());
            }

            private static @NotNull Provider<String> getDefaultVersion(ProviderFactory providers) {
                return providers.gradleProperty("parchment.version").map(String::trim);
            }

            @Input
            abstract Property<String> getVersion();

            @Input
            abstract Property<String> getMinecraftVersion();

            private void from(ObjectFactory objects, ProviderFactory providers, PlatformParchment parchment, PlatformMinecraft minecraft) {
                getVersion().set(
                        parchment.getVersion().orElse(getDefaultVersion(providers))
                );
                getMinecraftVersion().set(
                        parchment.getMinecraftVersion().orElse(getDefaultMinecraftVersion(providers, minecraft))
                );
            }
        }

        public abstract static class PlatformDefaults {

            @Inject
            public PlatformDefaults(final ObjectFactory objects, final ProviderFactory providers) {
                getJetbrainsAnnotationsVersion().convention(getDefaultAnnotationsVersion(providers));
            }

            private static @NotNull Provider<String> getDefaultAnnotationsVersion(ProviderFactory providers) {
                return providers.gradleProperty("jetbrains.annotations.version").map(String::trim);
            }

            public abstract Property<String> getJetbrainsAnnotationsVersion();

            private void from(ObjectFactory objects, ProviderFactory providers, PlatformDefaults defaults) {
                getJetbrainsAnnotationsVersion().set(
                        defaults.getJetbrainsAnnotationsVersion().orElse(getDefaultAnnotationsVersion(providers))
                );
            }
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

        public PlatformDefaults getDefaults() {
            return defaults;
        }

        public void defaults(Action<? super PlatformDefaults> action) {
            action.execute(getDefaults());
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
