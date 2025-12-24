package com.communi.suggestu.placitum.platform;

import com.communi.suggestu.placitum.core.AbstractPlatformProject;
import com.communi.suggestu.placitum.core.CommonPlatformProject;
import com.communi.suggestu.placitum.core.CorePlatformProject;
import com.communi.suggestu.placitum.core.FabricPlatformProject;
import com.communi.suggestu.placitum.core.NeoForgePlatformProject;
import com.communi.suggestu.placitum.core.PluginPlatformProject;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ProviderFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class SettingsPlatformExtension {

    public static final String EXTENSION_NAME = "platforms";

    private final Settings settings;
    private final Map<String, ProjectDescriptor> knownDynamicDescriptors = new HashMap<>();

    private final AbstractPlatformProject.Platform defaults;

    @Inject
    public SettingsPlatformExtension(Settings settings) {
        this.settings = settings;
        this.defaults = getObjectFactory().newInstance(Platform.class, getObjectFactory(), settings.getProviders());
    }

    @Inject
    public abstract ObjectFactory getObjectFactory();

    public void common(final String path) {
        registerProject(path, ProjectDescriptor.common(p -> new CommonPlatformProject()));
    }

    public void core(final String path) {
        if (knownDynamicDescriptors.values().stream().anyMatch(ProjectDescriptor::isCore)) {
            throw new IllegalStateException("Core project already registered");
        }

        registerProject(path, ProjectDescriptor.core(p -> new CorePlatformProject()));
    }

    public void plugin(final String path) {
        registerProject(path, ProjectDescriptor.plugin(p -> new PluginPlatformProject()));
    }

    public void fabric(final String path) {
        registerProject(path, ProjectDescriptor.loaderSpecific(p -> p.getObjects().newInstance(FabricPlatformProject.class)));
    }

    public void neoforge(final String path) {
        registerProject(path, ProjectDescriptor.loaderSpecific(p -> p.getObjects().newInstance(NeoForgePlatformProject.class)));
    }

    public AbstractPlatformProject.Platform getDefaults() {
        return defaults;
    }

    public void defaults(Action<AbstractPlatformProject.Platform> action) {
        action.execute(defaults);
    }

    private void registerProject(String path, ProjectDescriptor factory) {
        settings.include(path);
        knownDynamicDescriptors.put(path, factory);
    }

    @Nullable
    public Function<Project, IPlatformProject> findProject(String path) {
        if (!knownDynamicDescriptors.containsKey(path)) {
            return null;
        }

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

    public Set<String> getPluginProjectPaths() {
        return knownDynamicDescriptors.entrySet()
            .stream()
            .filter(entry -> entry.getValue().isPlugin())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    private record ProjectDescriptor(boolean isCore, boolean isCommon, boolean isPlugin, Function<Project, IPlatformProject> builder) {
        public static ProjectDescriptor core(Function<Project, IPlatformProject> builder) {
            return new ProjectDescriptor(true, false, false, builder);
        }

        public static ProjectDescriptor common(Function<Project, IPlatformProject> builder) {
            return new ProjectDescriptor(false, false, true, builder);
        }

        public static ProjectDescriptor plugin(Function<Project, IPlatformProject> builder) {
            return new ProjectDescriptor(false, true, true, builder);
        }

        public static ProjectDescriptor loaderSpecific(Function<Project, IPlatformProject> builder) {
            return new ProjectDescriptor(false, false, false, builder);
        }
    }

    public abstract static class Platform extends AbstractPlatformProject.Platform {

        @Inject
        public Platform(ObjectFactory objects, ProviderFactory providers) {
            super(objects, providers);
        }
    }
}
