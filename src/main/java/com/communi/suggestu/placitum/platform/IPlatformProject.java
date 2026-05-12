package com.communi.suggestu.placitum.platform;

import com.communi.suggestu.placitum.core.AbstractPlatformProject;
import org.gradle.api.Project;

/**
 * Represents a platform project.
 */
public interface IPlatformProject {
    /**
     * Configures the project with the necessary settings and plugins.
     *
     * @param project        The project to configure.
     * @param projectModules The modules that make up this project.
     * @param defaults       The defaults to apply
     */
    void configure(Project project, final ProjectModules projectModules, AbstractPlatformProject.Platform defaults);
}
