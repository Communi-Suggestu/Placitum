package com.communi.suggestu.placitum;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.jetbrains.annotations.NotNull;

public class GeneralPlugin implements Plugin<Object> {
    @Override
    public void apply(@NotNull Object target) {
        if (target instanceof Project project) {
            project.getPlugins().apply(ProjectPlugin.class);
        } else if (target instanceof Settings settings) {
            settings.getPlugins().apply(SettingsPlugin.class);
        }
    }
}
