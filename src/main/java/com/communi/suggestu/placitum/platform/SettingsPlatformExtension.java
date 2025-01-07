package com.communi.suggestu.placitum.platform;

import com.communi.suggestu.placitum.core.CorePlatformProject;
import com.communi.suggestu.placitum.core.NeoForgePlatformProject;
import org.gradle.api.initialization.Settings;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SettingsPlatformExtension {

    public static final String EXTENSION_NAME = "platforms";

    private final Settings settings;
    private final Map<String, ProjectDescriptor> knownDynamicDescriptors = new HashMap<>();

    @Inject
    public SettingsPlatformExtension(Settings settings) {
        this.settings = settings;
    }

    public void common(final String path) {
        registerProject(path, ProjectDescriptor.common(NeoForgePlatformProject::new));
    }

    public void core(final String path) {
        if (knownDynamicDescriptors.values().stream().anyMatch(ProjectDescriptor::isCore)) {
            throw new IllegalStateException("Core project already registered");
        }

        registerProject(path, ProjectDescriptor.core(CorePlatformProject::new));
    }

    public void fabric(final String path) {
        registerProject(path, ProjectDescriptor.loaderSpecific(NeoForgePlatformProject::new));
    }

    public void neoforge(final String path) {
        registerProject(path, ProjectDescriptor.loaderSpecific(NeoForgePlatformProject::new));
    }

    private void registerProject(String path, ProjectDescriptor factory) {
        settings.include(path);
        knownDynamicDescriptors.put(path, factory);
    }

    @Nullable
    public Supplier<IPlatformProject> findProject(String path) {
        return knownDynamicDescriptors.get(path).builder();
    }

    public Set<String> getCommonProjectPaths() {
        return knownDynamicDescriptors.entrySet()
                .stream()
                .filter(entry -> entry.getValue().isCommon())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public String getCoreProjectPath() {
        return knownDynamicDescriptors.entrySet()
                .stream()
                .filter(entry -> entry.getValue().isCore())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Core project not registered"));
    }

    private record ProjectDescriptor(boolean isCore, boolean isCommon, Supplier<IPlatformProject> builder) {
        public static ProjectDescriptor core(Supplier<IPlatformProject> builder) {
            return new ProjectDescriptor(true, false, builder);
        }

        public static ProjectDescriptor common(Supplier<IPlatformProject> builder) {
            return new ProjectDescriptor(false, true, builder);
        }

        public static ProjectDescriptor loaderSpecific(Supplier<IPlatformProject> builder) {
            return new ProjectDescriptor(false, false, builder);
        }
    }
}
