package com.communi.suggestu.placitum.core;

import com.communi.suggestu.placitum.platform.IPlatformProject;
import com.communi.suggestu.placitum.platform.ProjectModules;
import net.neoforged.gradle.common.CommonPlugin;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.util.Locale;

public class RootPlatformProject implements IPlatformProject {

	@Override
	public void configure(final Project project, final ProjectModules projectModules, final AbstractPlatformProject.Platform defaults) {
		//On a root project we need to be a bit more tricky, we for sure need to apply the NeoForge common project
		project.getPlugins().apply(CommonPlugin.class);

		final Platform platform = registerPlatformExtension(project, defaults);

		//Also configure the JDK Toolchain
		final JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
		java.getToolchain().getLanguageVersion().set(platform.getJava().getVersion().map(JavaLanguageVersion::of));
	}

	private Platform registerPlatformExtension(Project project, AbstractPlatformProject.Platform defaults) {
		return project.getExtensions().create(Platform.class, AbstractPlatformProject.Platform.EXTENSION_NAME, Platform.class, project, defaults);
	}

	/**
     * The core platform.
     */
    public abstract static class Platform {

		public static final String EXTENSION_NAME = "platform";

		private final PlatformJava     java;

		protected Platform(final ObjectFactory objects, final ProviderFactory providers) {
			this.java = objects.newInstance(PlatformJava.class, objects, providers);
		}

		@Inject
		public Platform(final Project project, final AbstractPlatformProject.Platform settings) {
			this(project.getObjects(), project.getProviders());
			this.java.from(project.getObjects(), project.getProviders(), settings.getJava());
		}

		@Nested
		public Platform.PlatformJava getJava() {
			return java;
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

			private void from(ObjectFactory objects, ProviderFactory providers, AbstractPlatformProject.Platform.PlatformJava java) {
				getVersion().set(
						java.getVersion().orElse(getDefaultJavaVersion(providers))
				);
			}
		}
	}
}
