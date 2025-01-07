package com.communi.suggestu.placitum.platform;

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
     */
    void configure(Project project, String coreCodeProject, Set<String> commonProjects);
}
