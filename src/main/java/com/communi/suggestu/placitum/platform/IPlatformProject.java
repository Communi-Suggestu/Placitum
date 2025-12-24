package com.communi.suggestu.placitum.platform;

import com.communi.suggestu.placitum.core.AbstractPlatformProject;
import org.gradle.api.Project;

import java.util.Set;

/**
 * Represents a platform project.
 */
public interface IPlatformProject {
    /**
     * Configures the project with the necessary settings and plugins.
     *
     * @param project         The project to configure.
     * @param coreCodeProject
     * @param commonProjects  The set of core projects.
     * @param pluginProjects  The set of core projects.
     * @param defaults
     */
    void configure(Project project, String coreCodeProject, final Set<String> commonProjects, Set<String> pluginProjects, AbstractPlatformProject.Platform defaults);
}
