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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.fs.config.ConfigurationException;
import org.apache.jackrabbit.vault.util.Constants;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jetbrains.annotations.Nullable;

/**
 * Common ancestor of all mojos dealing with package metadata.
 */
public abstract class AbstractMetadataPackageMojo extends AbstractMojo {

    private static final String PROPERTIES_EMBEDDEDFILESMAP_KEY = "embeddedfiles.map";

    protected String getProjectRelativeFilePath(File file) {
        return getRelativePath(project.getBasedir().toPath(), file.toPath());
    }

    protected static String getRelativePath(Path base, Path file) {
        if (file.startsWith(base)) {
            return "'" + base.relativize(file).toString() + "'"; 
        } else {
            return "'" + file.toString() + "'";
        }
    }

    protected static File getFirstExistingDirectory(File[] directories) {
        for (File dir: directories) {
            if (dir.exists() && dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    /**
     * The Maven project.
     */
    @Parameter(property = "project", readonly = true, required = true)
    protected MavenProject project;


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
     * The output directory for goal "generate-metadata" and additional input directory containing the 
     * metadata to be packaged up into the content package for goal "package".
     * The directory name is suffixed with {@code -<classifier>} in case a {@link #classifier} is used.
     * In case of the "package" goal this falls back to the non-suffixed directory name in case the one with
     * suffix does not exist.
     */
    @Parameter(
            defaultValue = "${project.build.directory}/vault-work",
            required = true)
    private File workDirectory;

    /**
     * Optional classifier to add to the generated package. If given, the artifact will be attached
     * as a supplemental artifact having this classifier.
     * Also all generated metadata will be pushed to {@code <workDirectory>-<classifier>} and will preferably
     * be looked up from there. In addition the embedded file names will be exchanged leveraging a classifier specific property.
     * @since 1.1.4
     */
    @Parameter(property = "vault.classifier")
    protected String classifier = "";

    /**
     * Adds a path prefix to all resources. Useful for shallower source trees.
     * This does not apply to files in {@link #workDirectory} nor {@link #metaInfVaultDirectory}
     * but e.g. is relevant for the default filter and for the jcr_root of the package.
     * Must start with "/" if not empty. As separator only forward slashes are allowed.
     * The trailing slash is automatically appended if not there.
     */
    @Parameter(property = "vault.prefix", defaultValue = "")
    String prefix = "";


    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 1.1.0
     */
    @Parameter( defaultValue = "${project.build.outputTimestamp}" )
    protected String outputTimestamp;
    
    
    public void setPrefix(String prefix) {
        if (prefix.startsWith("/")) {
            throw new IllegalArgumentException("Parameter 'prefix' must start with a slash!");
        }
        if (prefix.length() > 0 && !prefix.endsWith("/")) {
            prefix += "/";
        }
        this.prefix = prefix;
    }


    /**
     * Sets the map of embedded files as project properties as a helper to pass data between the goals.
     * It will always use classifier specific project properties.
     * @param embeddedFiles map of embedded files (key=destination file name, value = source file)
     */
    @SuppressWarnings("unchecked")
    void setEmbeddedFilesMap(Map<String, File> embeddedFiles) {
        getPluginContext().put(PROPERTIES_EMBEDDEDFILESMAP_KEY + classifier, embeddedFiles);
    }

    /**
     * Reads the map of embedded files from the project properties. This is a helper to pass data between the goals.
     * It will preferably use the embedded files from the classifier specific project properties.
     * @return the map of embedded files (key=destination file name, value = source file)
     */
    Map<String, File> getEmbeddedFilesMap() {
        Map<String, File> map = getEmbeddedFilesMap(PROPERTIES_EMBEDDEDFILESMAP_KEY + classifier);
        if (map == null) {
            getLog().debug("Using regular embedded files map as classifier specific one does not exist!");
            map = getEmbeddedFilesMap(PROPERTIES_EMBEDDEDFILESMAP_KEY);
        }
        return map == null ? Collections.emptyMap() : map;
    }

    @SuppressWarnings("unchecked")
    private @Nullable Map<String, File> getEmbeddedFilesMap(String key) {
        Object value = getPluginContext().get(key);
        if (value == null) {
            return null;
        } else {
            if (value instanceof Map<?,?>) {
                return (Map<String, File>) value;
            } else {
                throw new IllegalStateException("The Maven property " + key + " is not containing a Map but rather " + value.getClass());
            }
        }
    }

    /**
     * 
     * @param isForWriting
     * @return the (potentially classifier-specific) work directory
     */
    File getWorkDirectory(boolean isForWriting) {
        return getWorkDirectory(getLog(), isForWriting, workDirectory, classifier);
    }

    static File getWorkDirectory(Log log, boolean isForWriting, File defaultWorkDirectory, String classifier) {
        if (StringUtils.isNotBlank(classifier)) {
            File classifierWorkDirectory = new File(defaultWorkDirectory.toString() + "-" + classifier);
            if (!isForWriting) {
                // fall back to regular work directory if work dir for classifier does not exist
                if (!classifierWorkDirectory.exists()) {
                    log.warn("Using regular workDirectory " + defaultWorkDirectory + " as classifier specific workDirectory does not exist at " + classifierWorkDirectory);
                    return defaultWorkDirectory;
                }
            }
            return classifierWorkDirectory;
        } else {
            return defaultWorkDirectory;
        }
    }

    /**
     * 
     * @return the META-INF/vault directory below the (classifier-specific) {@link #workDirectory}
     */
    File getGeneratedVaultDir(boolean isForWriting) {
        return new File(getWorkDirectory(isForWriting), Constants.META_DIR);
    }

    File getGeneratedManifestFile(boolean isForWriting) {
        return new File(getWorkDirectory(isForWriting), JarFile.MANIFEST_NAME);
    }

    File getGeneratedFilterFile(boolean isForWriting) {
        return new File(getGeneratedVaultDir(isForWriting), Constants.FILTER_XML);
    }

    /**
     * 
     * @return the first matching directory from the list given in {@link #metaInfVaultDirectory}
     */
    protected File getMetaInfVaultSourceDirectory() {
        return getMetaInfVaultSourceDirectory(metaInfVaultDirectory, getLog());
    }

    protected static File getMetaInfVaultSourceDirectory(File[] metaInfVaultDirectory, Log log) {
        File metaInfDirectory = getFirstExistingDirectory(metaInfVaultDirectory);
        if (metaInfDirectory != null) {
            log.info("Using META-INF/vault from " + metaInfDirectory.getPath());
        }
        return metaInfDirectory;
    }

    protected static File resolveArtifact(org.eclipse.aether.artifact.Artifact artifact, RepositorySystem repoSystem, RepositorySystemSession repoSession, List<RemoteRepository> repositories) throws MojoExecutionException {
        ArtifactRequest req = new ArtifactRequest(artifact, repositories, null);
        ArtifactResult resolutionResult;
        try {
            resolutionResult = repoSystem.resolveArtifact(repoSession, req);
            return resolutionResult.getArtifact().getFile();
        } catch( ArtifactResolutionException e ) {
            throw new MojoExecutionException("Artifact " + artifact + " could not be resolved.", e);
        }
    }

    Filters loadGeneratedFilterFile() throws IOException, ConfigurationException {
        // load filters for further processing
        Filters filters = new Filters();
        filters.load(getGeneratedFilterFile(false));
        return filters;
    }

 }
