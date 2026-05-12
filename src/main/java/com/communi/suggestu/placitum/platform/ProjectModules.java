package com.communi.suggestu.placitum.platform;

import com.google.common.collect.Sets;

import java.util.Set;

public record ProjectModules(
    String coreCodeProject,
    Set<String> commonProjects,
    Set<String> pluginProjects,
    Set<String> devPluginProjects) {
}