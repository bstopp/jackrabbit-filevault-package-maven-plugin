/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.filevault.maven.packaging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.filevault.maven.packaging.validator.impl.context.DirectoryValidationContext;
import org.apache.jackrabbit.vault.fs.config.ConfigurationException;
import org.apache.jackrabbit.vault.util.Constants;
import org.apache.jackrabbit.vault.validation.ValidationExecutor;
import org.apache.jackrabbit.vault.validation.ValidationViolation;
import org.apache.jackrabbit.vault.validation.spi.ValidationContext;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.lifecycle.LifecycleNotFoundException;
import org.apache.maven.lifecycle.LifecyclePhaseNotFoundException;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.MojoNotFoundException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.prefix.NoPluginFoundForPrefixException;
import org.apache.maven.plugin.version.PluginVersionResolutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.AbstractScanner;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.Scanner;

/** 
 * Validates individual files with all registered validators. This is only active for incremental builds (i.e. inside m2e)
 * or when mojo "validate-package" is not executed in the current Maven execution
 * @see <a href="https://jackrabbit.apache.org/filevault-package-maven-plugin/validators.html">Validators</a>
 */
@Mojo(name = "validate-files", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class ValidateFilesMojo extends AbstractValidateMojo {

    //-----
    // Start: Copied from AbstractMetadataPackageMojo
    // -----
    /**
     * The directory that contains the META-INF/vault. Multiple directories can be specified as a comma separated list,
     * which will act as a search path and cause the plugin to look for the first existing directory.
     * <p>
     * This directory is added as fileset to the package archiver before the the {@link #workDirectory}. This means that
     * files specified in this directory have precedence over the one present in the {@link #workDirectory}. For example,
     * if this directory contains a {@code properties.xml} it will not be overwritten by the generated one. A special
     * case is the {@code filter.xml} which will be merged with inline filters if present.
     */
    @Parameter(property = "vault.metaInfVaultDirectory", required = true, defaultValue = "${project.basedir}/META-INF/vault,"
            + "${project.basedir}/src/main/META-INF/vault," + "${project.basedir}/src/main/content/META-INF/vault,"
            + "${project.basedir}/src/content/META-INF/vault")
    File[] metaInfVaultDirectory;

    /**
     * The directory containing the metadata to be packaged up into the content package.
     * Basically containing all files/folders being generated by goal "generate-metadata".
     */
    @Parameter(
            defaultValue = "${project.build.directory}/vault-work",
            required = true)
    File workDirectory;
    //-----
    // End: Copied from AbstractMetadataPackageMojo
    // -----
    
    //-----
    // Start: Copied from AbstractSourceAndMetadataPackageMojo
    // -----
    

    /**
     * The directory containing the content to be packaged up into the content
     * package.
     *
     * This property is deprecated; use {@link #jcrRootSourceDirectory} instead.
     */
    @Deprecated
    @Parameter
    private File builtContentDirectory;

    /**
     * The directory that contains the jcr_root of the content. Multiple directories can be specified as a comma separated list,
     * which will act as a search path and cause the plugin to look for the first existing directory.
     */
    @Parameter(property = "vault.jcrRootSourceDirectory", required = true, defaultValue = "${project.basedir}/jcr_root,"
            + "${project.basedir}/src/main/jcr_root," + "${project.basedir}/src/main/content/jcr_root,"
            + "${project.basedir}/src/content/jcr_root," + "${project.build.outputDirectory}")
    private File[] jcrRootSourceDirectory;

    /**
     * The file name patterns to exclude in addition to the ones listed in
     * {@link AbstractScanner#DEFAULTEXCLUDES}. The format of each pattern is described in {@link DirectoryScanner}.
     * The comparison is against the path relative to the according filter root.
     * Since this is hardly predictable it is recommended to use only filename/directory name patterns here 
     * but not take into account file system hierarchies!
     * <p>
     * Each value is either a regex pattern if enclosed within {@code %regex[} and {@code ]}, otherwise an 
     * <a href="https://ant.apache.org/manual/dirtasks.html#patterns">Ant pattern</a>.
     */
    @Parameter(property = "vault.excludes", defaultValue = "**/.vlt,**/.vltignore", required = true)
    protected String[] excludes;
    //-----
    // End: Copied from AbstractSourceAndMetadataPackageMojo
    // -----

    @Component
    protected LifecycleExecutor lifecycleExecutor;

    private static final String PLUGIN_KEY = "org.apache.jackrabbit:filevault-package-maven-plugin";

    public ValidateFilesMojo() {
    }


    @Override
    public void doExecute() throws MojoExecutionException, MojoFailureException {
        final List<String> allGoals;
        if (session != null) {
            allGoals = session.getGoals();
            getLog().debug("Following goals are detected: " + StringUtils.join(allGoals, ", "));
        } else {
            getLog().debug("MavenSession not available. Maybe executed by m2e.");
            allGoals = Collections.emptyList();
        }
        // is another mojo from this plugin called in this maven session later on?
        try {
            if (!buildContext.isIncremental() && isMojoGoalExecuted(lifecycleExecutor, "validate-package", allGoals.toArray(new String[0]))) { // how to detect that "install" contains "package"? how to resolve the given goals?
                getLog().info("Skip this mojo as this is not an incremental build and 'validate-package' is executed later on!");
                return;
            }
        } catch (PluginNotFoundException | PluginResolutionException | PluginDescriptorParsingException | MojoNotFoundException
                | NoPluginFoundForPrefixException | InvalidPluginDescriptorException | PluginVersionResolutionException
                | LifecyclePhaseNotFoundException | LifecycleNotFoundException | PluginManagerException e1) {
            getLog().warn("Could not determine plugin executions", e1);
        }
        disableChecksOnlyWorkingForPackages();
        try {
            File metaInfoVaultSourceDirectory = AbstractMetadataPackageMojo.getMetaInfVaultSourceDirectory(metaInfVaultDirectory, getLog());
            File metaInfRootDirectory = null;
            if (metaInfoVaultSourceDirectory != null) {
                metaInfRootDirectory = metaInfoVaultSourceDirectory.getParentFile();
            }
            File generatedMetaInfRootDirectory = new File(workDirectory, Constants.META_INF);
            getLog().info("Using generatedMetaInfRootDirectory: " + generatedMetaInfRootDirectory + " and metaInfRootDir: " + metaInfRootDirectory);
            ValidationContext context = new DirectoryValidationContext(generatedMetaInfRootDirectory, metaInfRootDirectory, resolver, getLog());
            ValidationExecutor executor = validationExecutorFactory.createValidationExecutor(context, false, false, getValidatorSettingsForPackage(context.getProperties().getId(), false));
            if (executor == null) {
                throw new MojoExecutionException("No registered validators found!");
            }
            validationHelper.printUsedValidators(getLog(), executor, context, true);
            if (metaInfRootDirectory != null) {
                validateDirectory(executor, metaInfRootDirectory, true);
            }
            validateDirectory(executor, generatedMetaInfRootDirectory, true);
            File jcrSourceDirectory = AbstractSourceAndMetadataPackageMojo.getJcrSourceDirectory(jcrRootSourceDirectory, builtContentDirectory, getLog());
            if (jcrSourceDirectory != null) {
                validateDirectory(executor, jcrSourceDirectory, false);
            }
            validationHelper.printMessages(executor.done(), getLog(), buildContext, project.getBasedir().toPath());
        } catch (IOException | ConfigurationException e) {
            throw new MojoFailureException("Could not execute validation", e);
        }
        validationHelper.failBuildInCaseOfViolations(failOnValidationWarnings);
    }

    private void validateDirectory(ValidationExecutor executor, File baseDir, boolean isMetaInf) {
        Scanner scanner = buildContext.newScanner(baseDir);
        // make sure filtering does work equally as within the package goal
        scanner.setExcludes(excludes);
        scanner.addDefaultExcludes();
        scanner.scan();
        getLog().info("Scanning baseDir '" + baseDir + "'...");
        for (String relativeFile : scanner.getIncludedFiles()) {
            validateFile(executor, baseDir, isMetaInf, relativeFile);
        }
        for (String relativeFile : scanner.getIncludedDirectories()) {
            validateFolder(executor, baseDir, isMetaInf, relativeFile);
        }
    }


    private void validateFile(ValidationExecutor executor, File baseDir, boolean isMetaInf, String relativeFile) {
        File absoluteFile = new File(baseDir, relativeFile);
        validationHelper.clearPreviousValidationMessages(buildContext, absoluteFile);
        getLog().debug("Validating file '" + absoluteFile + "'...");
        try (InputStream input = new FileInputStream(absoluteFile)) {
            validateInputStream(executor, input, baseDir, isMetaInf, relativeFile);
        } catch (FileNotFoundException e) {
            getLog().error("Could not find file " + absoluteFile, e);
        } catch (IOException e) {
            getLog().error("Could not validate file " + absoluteFile, e);
        }
    }
    
    private void validateFolder(ValidationExecutor executor, File baseDir, boolean isMetaInf, String relativeFile) {
        File absoluteFile = new File(baseDir, relativeFile);
        validationHelper.clearPreviousValidationMessages(buildContext, absoluteFile);
        getLog().debug("Validating folder '" + absoluteFile + "'...");
        try {
            validateInputStream(executor, null, baseDir, isMetaInf, relativeFile);
        } catch (IOException e) {
            getLog().error("Could not validate folder " + absoluteFile, e);
        }
    }
    
    private void validateInputStream(ValidationExecutor executor, InputStream input, File baseDir, boolean isMetaInf, String relativeFile) throws IOException {
        final Collection<ValidationViolation> messages;
        if (isMetaInf) {
            messages = executor.validateMetaInf(input, Paths.get(relativeFile), baseDir.toPath());
        } else {
            messages = executor.validateJcrRoot(input, Paths.get(relativeFile), baseDir.toPath());
        }
        validationHelper.printMessages(messages, getLog(), buildContext, project.getBasedir().toPath());
    }

    /**
     * Checks if a certain goal is executed at some point in time in the same Maven Session
     * @param lifecycleExecutor
     * @param mojoGoal
     * @param goals
     * @return
     * @throws PluginNotFoundException
     * @throws PluginResolutionException
     * @throws PluginDescriptorParsingException
     * @throws MojoNotFoundException
     * @throws NoPluginFoundForPrefixException
     * @throws InvalidPluginDescriptorException
     * @throws PluginVersionResolutionException
     * @throws LifecyclePhaseNotFoundException
     * @throws LifecycleNotFoundException
     * @throws PluginManagerException
     * @see <a href="https://github.com/apache/maven/blob/master/maven-core/src/main/java/org/apache/maven/lifecycle/DefaultLifecycleExecutor.java">DefaultLifecycleExecutor</a>
     */
    private boolean isMojoGoalExecuted(LifecycleExecutor lifecycleExecutor, String mojoGoal, String... goals) throws PluginNotFoundException, PluginResolutionException, PluginDescriptorParsingException, MojoNotFoundException, NoPluginFoundForPrefixException, InvalidPluginDescriptorException, PluginVersionResolutionException, LifecyclePhaseNotFoundException, LifecycleNotFoundException, PluginManagerException {
        if (goals.length == 0) {
            return false;
        }
        MavenExecutionPlan executionPlan = lifecycleExecutor.calculateExecutionPlan(session, goals);
        for (MojoExecution mojoExecution : executionPlan.getMojoExecutions()) {
            if (PLUGIN_KEY.equals(mojoExecution.getPlugin().getKey()) && mojoGoal.equals(mojoExecution.getGoal())) {
                return true;
            }
        }
        return false;
    }
}
