/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.tooling.impl.util;

import static com.itsaky.androidide.utils.ILogger.newInstance;

import com.android.builder.model.v2.ide.SourceProvider;
import com.android.builder.model.v2.ide.SourceSetContainer;
import com.android.builder.model.v2.models.AndroidProject;
import com.itsaky.androidide.tooling.api.model.IdeAndroidModule;
import com.itsaky.androidide.tooling.api.model.IdeGradleProject;
import com.itsaky.androidide.tooling.api.model.IdeGradleTask;
import com.itsaky.androidide.tooling.api.model.android.IdeSourceSetContainer;
import com.itsaky.androidide.tooling.api.model.util.ProjectBuilder;
import com.itsaky.androidide.utils.ILogger;

import org.gradle.tooling.ConfigurableLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.UnknownModelException;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.GradleTask;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Function;

/**
 * @author Akash Yadav
 */
public class ProjectReader {

    private static final ILogger LOG = newInstance("test");
    private static Function<File, File> fileCopier = it -> new File(it.getAbsolutePath());

    public static IdeGradleProject read(ProjectConnection connection) {
        final var gradleModel = connection.getModel(GradleProject.class);
        if (gradleModel == null) {
            return null;
        }

        return buildProjectModel(connection, gradleModel);
    }

    private static IdeGradleProject buildProjectModel(
            ProjectConnection connection, GradleProject gradleModel) {
        try {
            final var modelBuilder = connection.model(AndroidProject.class);
            addProperty(modelBuilder, AndroidProject.PROPERTY_BUILD_MODEL_ONLY, true);
            final var android = modelBuilder.get();

            if (android == null) {
                throw new UnknownModelException(
                        "Project " + gradleModel.getPath() + " is not an Android project.");
            }

            return buildAndroidProjectModel(gradleModel, android);
        } catch (Throwable error) {
            LOG.error("Unable to build android project model", error);
            try {
                return buildGradleProjectModel(gradleModel);
            } catch (Throwable e) {
                LOG.error("Unable to create model for project", e);
                return null;
            }
        }
    }

    private static IdeAndroidModule buildAndroidProjectModel(
            GradleProject gradle, AndroidProject android) {
        LOG.debug("Building android module model for project:", gradle.getPath());
        final var builder = new ProjectBuilder();
        builder.setName(gradle.getName());
        builder.setDescription(gradle.getDescription());
        builder.setPath(gradle.getPath());
        builder.setProjectDir(gradle.getProjectDirectory());
        builder.setBuildDir(gradle.getBuildDirectory());
        builder.setBuildScript(gradle.getBuildScript().getSourceFile());
        builder.setProjectType(android.getProjectType());
        builder.setMainSourceSet(android.getMainSourceSet());
        builder.setBuildTypeSourceSets(android.getBuildTypeSourceSets());
        builder.setProductFlavorSourceSets(android.getProductFlavorSourceSets());
        builder.setVariants(android.getVariants());
        builder.setBootClasspath(android.getBootClasspath());
        builder.setJavaCompileOptions(android.getJavaCompileOptions());
        builder.setBuildFolder(android.getBuildFolder());
        builder.setResourcePrefix(android.getResourcePrefix());
        builder.setDynamicFeatures(android.getDynamicFeatures());
        builder.setViewBindingOptions(android.getViewBindingOptions());
        builder.setFlags(android.getFlags());
        builder.setLintRuleJars(android.getLintRuleJars());

        final var module = builder.buildAndroidModule();
        addSubprojects(gradle, module);
        addTasks(gradle, module);

        return module;
    }

    private static SourceSetContainer copySourceSetContainer(SourceSetContainer source) {
        return new IdeSourceSetContainer(
                copySourceProvider(source.getAndroidTestSourceProvider()),
                copySourceProvider(source.getSourceProvider()),
                copySourceProvider(source.getTestFixturesSourceProvider()),
                copySourceProvider(source.getUnitTestSourceProvider()));
    }

    private static SourceProvider copySourceProvider(SourceProvider androidTestSourceProvider) {
        return null;
        //        return new IdeSourceProvider(
        //                copyCollection(androidTestSourceProvider.getAidlDirectories(),
        // fileCopier),
        //                copyCollection(androidTestSourceProvider.getAssetsDirectories(),
        // fileCopier),
        //                copyCollection(androidTestSourceProvider.getJavaDirectories(),
        // fileCopier),
        //                copyCollection(androidTestSourceProvider.getJniLibsDirectories(),
        // fileCopier));
    }

    private static <E> Collection<E> copyCollection(
            @Nullable Collection<E> source, Function<E, E> copier) {
        final var result = new ArrayList<E>();
        if (source == null) {
            return result;
        }

        for (var el : source) {
            result.add(copier.apply(el));
        }
        return result;
    }

    private static void addProperty(
            ConfigurableLauncher<?> launcher, String property, Object value) {
        launcher.addArguments(String.format("-P%s=%s", property, value));
    }

    private static IdeGradleProject buildGradleProjectModel(GradleProject gradle) {
        final var builder = new ProjectBuilder();
        builder.setName(gradle.getName());
        builder.setDescription(gradle.getDescription());
        builder.setPath(gradle.getPath());
        builder.setProjectDir(gradle.getProjectDirectory());
        builder.setBuildDir(gradle.getBuildDirectory());
        builder.setBuildScript(gradle.getBuildScript().getSourceFile());

        final var project = builder.buildGradleProject();
        addSubprojects(gradle, project);
        addTasks(gradle, project);

        return project;
    }

    private static void addTasks(GradleProject gradle, IdeGradleProject project) {
        for (final var task : gradle.getTasks()) {
            project.getTasks().add(buildGradleTaskModel(project, task));
        }
    }

    private static void addSubprojects(GradleProject gradle, IdeGradleProject project) {
        for (final var subGradle : gradle.getChildren()) {
            LOG.debug("Subproject of", gradle.getPath(), "->", subGradle.getPath());
            final var connection =
                    GradleConnector.newConnector()
                            .forProjectDirectory(subGradle.getProjectDirectory())
                            .connect();
            final var sub = buildProjectModel(connection, subGradle);
            if (sub != null) {
                LOG.debug(
                        "Is project",
                        sub.getProjectPath(),
                        "an Android module?:",
                        sub instanceof IdeAndroidModule);
                project.getSubprojects().add(sub);
            }
        }
    }

    private static IdeGradleTask buildGradleTaskModel(IdeGradleProject project, GradleTask task) {
        return new IdeGradleTask(
                task.getName(),
                task.getDescription(),
                task.getGroup(),
                task.getPath(),
                task.getDisplayName(),
                task.isPublic(),
                project.getProjectPath());
    }
}
