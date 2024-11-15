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
    private final Map<String, IPlatformProject> knownDynamicDescriptors = new HashMap<>();

    @Inject
    public SettingsPlatformExtension(Settings settings) {
        this.settings = settings;
    }

    public void core(final String path) {
        registerProject(path, CorePlatformProject::new);
    }

    public void fabric(final String path) {
    }

    public void neoforge(final String path) {
        registerProject(path, NeoForgePlatformProject::new);
    }

    private void registerProject(String path, Supplier<IPlatformProject> factory) {
        settings.include(path);
        knownDynamicDescriptors.put(path, factory.get());
    }

    @Nullable
    public IPlatformProject findProject(String path) {
        return knownDynamicDescriptors.get(path);
    }

    public Set<String> getCoreProject() {
        return knownDynamicDescriptors.entrySet()
                .stream()
                .filter(entry -> entry.getValue() instanceof CorePlatformProject)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
