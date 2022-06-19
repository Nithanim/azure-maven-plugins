/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.maven.function;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.azure.maven.model.DeploymentResource;
import com.microsoft.azure.toolkit.lib.common.exception.AzureExecutionException;
import com.microsoft.azure.toolkit.lib.common.logging.Log;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.utils.Utils;
import com.microsoft.azure.toolkit.lib.legacy.function.bindings.Binding;
import com.microsoft.azure.toolkit.lib.legacy.function.bindings.BindingEnum;
import com.microsoft.azure.toolkit.lib.legacy.function.configurations.FunctionConfiguration;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.AnnotationHandler;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.AnnotationHandlerImpl;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.CommandHandler;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.CommandHandlerImpl;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.FunctionCoreToolsHandler;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.FunctionCoreToolsHandlerImpl;
import com.microsoft.azure.toolkit.lib.legacy.function.utils.IndexUtils;

/**
 * Generate configuration files (host.json, function.json etc.) and copy JARs to staging directory.
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public class PackageMojo extends AbstractFunctionMojo {
    public static final String SEARCH_FUNCTIONS = "Step 1 of 8: Searching for Azure Functions entry points";
    public static final String FOUND_FUNCTIONS = " Azure Functions entry point(s) found.";
    public static final String NO_FUNCTIONS = "Azure Functions entry point not found, plugin will exit.";
    public static final String GENERATE_CONFIG = "Step 2 of 8: Generating Azure Functions configurations";
    public static final String GENERATE_SKIP = "No Azure Functions found. Skip configuration generation.";
    public static final String GENERATE_DONE = "Generation done.";
    public static final String VALIDATE_CONFIG = "Step 3 of 8: Validating generated configurations";
    public static final String VALIDATE_SKIP = "No configurations found. Skip validation.";
    public static final String VALIDATE_DONE = "Validation done.";
    public static final String SAVING_HOST_JSON = "Step 4 of 8: Copying/creating host.json";
    public static final String SAVING_LOCAL_SETTINGS_JSON = "Step 5 of 8: Copying/creating local.settings.json";
    public static final String SAVE_FUNCTION_JSONS = "Step 6 of 8: Saving configurations to function.json";
    public static final String SAVE_SKIP = "No configurations found. Skip save.";
    public static final String SAVE_FUNCTION_JSON = "Starting processing function: ";
    public static final String SAVE_SUCCESS = "Successfully saved to ";
    public static final String COPY_JARS = "Step 7 of 8: Copying JARs to staging directory ";
    public static final String COPY_SUCCESS = "Copied successfully.";
    public static final String INSTALL_EXTENSIONS = "Step 8 of 8: Installing function extensions if needed";
    public static final String SKIP_INSTALL_EXTENSIONS_HTTP = "Skip install Function extension for HTTP Trigger Functions";
    public static final String INSTALL_EXTENSIONS_FINISH = "Function extension installation done.";
    public static final String BUILD_SUCCESS = "Successfully built Azure Functions.";

    public static final String FUNCTION_JSON = "function.json";
    public static final String HOST_JSON = "host.json";
    public static final String LOCAL_SETTINGS_JSON = "local.settings.json";
    public static final String EXTENSION_BUNDLE = "extensionBundle";
    private static final String AZURE_FUNCTIONS_JAVA_LIBRARY = "azure-functions-java-library";
    private static final String AZURE_FUNCTIONS_JAVA_CORE_LIBRARY = "azure-functions-java-core-library";
    private static final String DEFAULT_LOCAL_SETTINGS_JSON = "{ \"IsEncrypted\": false, \"Values\": " +
            "{ \"FUNCTIONS_WORKER_RUNTIME\": \"java\" } }";
    private static final String DEFAULT_HOST_JSON = "{\"version\":\"2.0\",\"extensionBundle\":" +
            "{\"id\":\"Microsoft.Azure.Functions.ExtensionBundle\",\"version\":\"[1.*, 2.0.0)\"}}\n";

    private static final BindingEnum[] FUNCTION_WITHOUT_FUNCTION_EXTENSION =
        {BindingEnum.HttpOutput, BindingEnum.HttpTrigger};
    private static final String EXTENSION_BUNDLE_ID = "Microsoft.Azure.Functions.ExtensionBundle";
    private static final String EXTENSION_BUNDLE_PREVIEW_ID = "Microsoft.Azure.Functions.ExtensionBundle.Preview";
    private static final String SKIP_INSTALL_EXTENSIONS_FLAG = "skipInstallExtensions flag is set, skip install extension";
    private static final String SKIP_INSTALL_EXTENSIONS_BUNDLE = "Extension bundle specified, skip install extension";
    private static final String CAN_NOT_FIND_ARTIFACT = "Cannot find the maven artifact, please run `mvn package` first.";
    //region Entry Point

    /**
     * Boolean flag to skip extension installation
     */
    @Parameter(property = "functions.skipInstallExtensions", defaultValue = "false")
    protected boolean skipInstallExtensions;

    @Override
    @AzureOperation(name = "functionapp.package", type = AzureOperation.Type.ACTION)
    protected void doExecute() throws AzureExecutionException {
        validateAppName();

        promptCompileInfo();

        final AnnotationHandler annotationHandler = getAnnotationHandler();

        Log.info("");
        Log.info(SEARCH_FUNCTIONS);
        IndexView index;
        try {
            index = buildIndex(annotationHandler);
        } catch (MalformedURLException e) {
            throw new AzureExecutionException("Invalid URL when resolving class path:" + e.getMessage(), e);
        }
        final Set<MethodInfo> methods = findAnnotatedMethods(index, annotationHandler);

        if (methods.size() == 0) {
            Log.info(NO_FUNCTIONS);
            return;
        }

        final Map<String, FunctionConfiguration> configMap = getFunctionConfigurations(index, annotationHandler, methods);

        trackFunctionProperties(configMap);
        validateFunctionConfigurations(configMap);

        final ObjectWriter objectWriter = getObjectWriter();

        try {
            copyHostJson();

            copyLocalSettingsJson();

            writeFunctionJsonFiles(objectWriter, configMap);

            copyJarsToStageDirectory();
        } catch (IOException e) {
            throw new AzureExecutionException("Cannot perform IO operations due to error:" + e.getMessage(), e);
        }

        final CommandHandler commandHandler = new CommandHandlerImpl();
        final FunctionCoreToolsHandler functionCoreToolsHandler = getFunctionCoreToolsHandler(commandHandler);
        final Set<BindingEnum> bindingClasses = this.getFunctionBindingEnums(configMap);

        installExtension(functionCoreToolsHandler, bindingClasses);

        Log.info(BUILD_SUCCESS);
    }

    //endregion

    //region Process annotations

    protected AnnotationHandler getAnnotationHandler() {
        return new AnnotationHandlerImpl();
    }

    protected Set<MethodInfo> findAnnotatedMethods(final IndexView index, final AnnotationHandler handler) {
        Log.info("");
        Log.info(SEARCH_FUNCTIONS);
        Set<MethodInfo> functions = handler.findFunctions(index);
        Log.info(functions.size() + FOUND_FUNCTIONS);
        return functions;
    }

    protected IndexView buildIndex(final AnnotationHandler handler) throws MalformedURLException {
        try {
            Log.debug("ClassPath to resolve: " + getTargetClassUrl());
            final List<URL> dependencyWithTargetClass = getDependencyArtifactUrls();
            dependencyWithTargetClass.add(getTargetClassUrl());
            return IndexUtils.enrichIndex(handler.buildIndex(dependencyWithTargetClass));
        } catch (NoClassDefFoundError e) {
            // fallback to reflect through artifact url, for shaded project(fat jar)
            Log.debug("ClassPath to resolve: " + getArtifactUrl());
            return IndexUtils.enrichIndex(handler.buildIndex(Collections.singletonList(getArtifactUrl())));
        }
    }

    protected URL getArtifactUrl() throws MalformedURLException {
        return this.getProject().getArtifact().getFile().toURI().toURL();
    }

    protected URL getTargetClassUrl() throws MalformedURLException {
        return outputDirectory.toURI().toURL();
    }

    /**
     * @return URLs for the classpath with compile scope needed jars
     */
    protected List<URL> getDependencyArtifactUrls() {
        final List<URL> urlList = new ArrayList<>();
        final List<String> runtimeClasspathElements = new ArrayList<>();
        try {
            runtimeClasspathElements.addAll(this.getProject().getRuntimeClasspathElements());
        } catch (DependencyResolutionRequiredException e) {
            Log.debug("Failed to resolve dependencies for compile scope, exception: " + e.getMessage());
        }
        for (final String element : runtimeClasspathElements) {
            final File f = new File(element);
            try {
                urlList.add(f.toURI().toURL());
            } catch (MalformedURLException e) {
                Log.debug("Failed to get URL for file: " + f.toString());
            }
        }
        return urlList;
    }

    //endregion

    //region Generate function configurations

    protected Map<String, FunctionConfiguration> getFunctionConfigurations(final IndexView index,
                                                                           final AnnotationHandler handler,
                                                                           final Set<MethodInfo> methods) throws AzureExecutionException {
        Log.info("");
        Log.info(GENERATE_CONFIG);
        final Map<String, FunctionConfiguration> configMap = handler.generateConfigurations(index, methods);
        if (configMap.size() == 0) {
            Log.info(GENERATE_SKIP);
        } else {
            final String scriptFilePath = getScriptFilePath();
            configMap.values().forEach(config -> config.setScriptFile(scriptFilePath));
            Log.info(GENERATE_DONE);
        }

        return configMap;
    }

    protected String getScriptFilePath() {
        return String.format("../%s.jar", getFinalName());
    }

    //endregion

    //region Validate function configurations

    protected void validateFunctionConfigurations(final Map<String, FunctionConfiguration> configMap) {
        Log.info("");
        Log.info(VALIDATE_CONFIG);
        if (configMap.size() == 0) {
            Log.info(VALIDATE_SKIP);
        } else {
            configMap.values().forEach(FunctionConfiguration::validate);
            Log.info(VALIDATE_DONE);
        }
    }

    //endregion

    //region Write configurations (host.json, function.json) to file

    protected void writeFunctionJsonFiles(final ObjectWriter objectWriter,
                                          final Map<String, FunctionConfiguration> configMap) throws IOException {
        Log.info("");
        Log.info(SAVE_FUNCTION_JSONS);
        if (configMap.size() == 0) {
            Log.info(SAVE_SKIP);
        } else {
            for (final Map.Entry<String, FunctionConfiguration> config : configMap.entrySet()) {
                writeFunctionJsonFile(objectWriter, config.getKey(), config.getValue());
            }
        }
    }

    protected void writeFunctionJsonFile(final ObjectWriter objectWriter, final String functionName,
                                         final FunctionConfiguration config) throws IOException {
        Log.info(SAVE_FUNCTION_JSON + functionName);
        final File functionJsonFile = Paths.get(getDeploymentStagingDirectoryPath(),
                functionName, FUNCTION_JSON).toFile();
        writeObjectToFile(objectWriter, config, functionJsonFile);
        Log.info(SAVE_SUCCESS + functionJsonFile.getAbsolutePath());
    }

    protected void copyHostJson() throws IOException {
        Log.info("");
        Log.info(SAVING_HOST_JSON);
        final File sourceHostJsonFile = new File(project.getBasedir(), HOST_JSON);
        final File destHostJsonFile = Paths.get(getDeploymentStagingDirectoryPath(), HOST_JSON).toFile();
        copyFilesWithDefaultContent(sourceHostJsonFile, destHostJsonFile, DEFAULT_HOST_JSON);
        Log.info(SAVE_SUCCESS + destHostJsonFile.getAbsolutePath());
    }

    protected void copyLocalSettingsJson() throws IOException {
        Log.info("");
        Log.info(SAVING_LOCAL_SETTINGS_JSON);
        final File sourceLocalSettingsJsonFile = new File(project.getBasedir(), LOCAL_SETTINGS_JSON);
        final File destLocalSettingsJsonFile = Paths.get(getDeploymentStagingDirectoryPath(), LOCAL_SETTINGS_JSON).toFile();
        copyFilesWithDefaultContent(sourceLocalSettingsJsonFile, destLocalSettingsJsonFile, DEFAULT_LOCAL_SETTINGS_JSON);
        Log.info(SAVE_SUCCESS + destLocalSettingsJsonFile.getAbsolutePath());
    }

    private static void copyFilesWithDefaultContent(File source, File dest, String defaultContent)
            throws IOException {
        if (source != null && source.exists()) {
            FileUtils.copyFile(source, dest);
        } else {
            FileUtils.write(dest, defaultContent, Charset.defaultCharset());
        }
    }

    protected void writeObjectToFile(final ObjectWriter objectWriter, final Object object, final File targetFile)
            throws IOException {
        targetFile.getParentFile().mkdirs();
        targetFile.createNewFile();
        objectWriter.writeValue(targetFile, object);
    }

    protected ObjectWriter getObjectWriter() {
        final DefaultPrettyPrinter.Indenter indenter = DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed(StringUtils.LF);
        final PrettyPrinter prettyPrinter = new DefaultPrettyPrinter().withObjectIndenter(indenter);
        return new ObjectMapper()
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writer(prettyPrinter);
    }

    //endregion

    //region Copy Jars to stage directory

    protected void copyJarsToStageDirectory() throws IOException, AzureExecutionException {
        final String stagingDirectory = getDeploymentStagingDirectoryPath();
        Log.info("");
        Log.info(COPY_JARS + stagingDirectory);
        final File libFolder = Paths.get(stagingDirectory, "lib").toFile();
        if (libFolder.exists()) {
            FileUtils.cleanDirectory(libFolder);
        }
        final Set<Artifact> artifacts = project.getArtifacts();
        final String libraryToExclude = artifacts.stream()
                .filter(artifact -> StringUtils.equalsAnyIgnoreCase(artifact.getArtifactId(), AZURE_FUNCTIONS_JAVA_CORE_LIBRARY))
                .map(Artifact::getArtifactId).findFirst().orElse(AZURE_FUNCTIONS_JAVA_LIBRARY);
        for (final Artifact artifact : artifacts) {
            if (!StringUtils.equalsIgnoreCase(artifact.getArtifactId(), libraryToExclude)) {
                FileUtils.copyFileToDirectory(artifact.getFile(), libFolder);
            }
        }
        FileUtils.copyFileToDirectory(getArtifactFile(), new File(stagingDirectory));
        Log.info(COPY_SUCCESS);
    }

    @Override
    public List<DeploymentResource> getResources() {
        final DeploymentResource resource = new DeploymentResource();
        resource.setDirectory(getBuildDirectoryAbsolutePath());
        resource.setTargetPath("/");
        resource.setFiltering(false);
        resource.setIncludes(Collections.singletonList("*.jar"));
        return Collections.singletonList(resource);
    }

    //endregion

    //region Azure Functions Core Tools task

    protected FunctionCoreToolsHandler getFunctionCoreToolsHandler(final CommandHandler commandHandler) {
        return new FunctionCoreToolsHandlerImpl(commandHandler);
    }

    protected void installExtension(final FunctionCoreToolsHandler handler,
                                    Set<BindingEnum> bindingEnums) throws AzureExecutionException {
        Log.info(INSTALL_EXTENSIONS);
        if (!isInstallingExtensionNeeded(bindingEnums)) {
            return;
        }
        handler.installExtension(new File(this.getDeploymentStagingDirectoryPath()),
                project.getBasedir());
        Log.info(INSTALL_EXTENSIONS_FINISH);
    }

    protected Set<BindingEnum> getFunctionBindingEnums(Map<String, FunctionConfiguration> configMap) {
        final Set<BindingEnum> result = new HashSet<>();
        configMap.values().forEach(configuration -> configuration.getBindings().
                forEach(binding -> result.add(binding.getBindingEnum())));
        return result;
    }

    protected boolean isInstallingExtensionNeeded(Set<BindingEnum> bindingTypes) {
        if (skipInstallExtensions) {
            Log.info(SKIP_INSTALL_EXTENSIONS_FLAG);
            return false;
        }
        final JsonObject hostJson = readHostJson();
        final String extensionBundleId = Optional.ofNullable(hostJson)
                .map(host -> host.getAsJsonObject(EXTENSION_BUNDLE))
                .map(extensionBundle -> extensionBundle.get("id"))
                .map(JsonElement::getAsString).orElse(null);
        if (StringUtils.equalsAnyIgnoreCase(extensionBundleId, EXTENSION_BUNDLE_ID, EXTENSION_BUNDLE_PREVIEW_ID)) {
            Log.info(SKIP_INSTALL_EXTENSIONS_BUNDLE);
            return false;
        }
        final boolean isNonHttpTriggersExist = bindingTypes.stream().anyMatch(binding ->
                !Arrays.asList(FUNCTION_WITHOUT_FUNCTION_EXTENSION).contains(binding));
        if (!isNonHttpTriggersExist) {
            Log.info(SKIP_INSTALL_EXTENSIONS_HTTP);
            return false;
        }
        return true;
    }

    protected JsonObject readHostJson() {
        final File hostJson = new File(project.getBasedir(), HOST_JSON);
        try (final FileInputStream fis = new FileInputStream(hostJson);
             final Scanner scanner = new Scanner(new BOMInputStream(fis))) {
            final String jsonRaw = scanner.useDelimiter("\\Z").next();
            return JsonParser.parseString(jsonRaw).getAsJsonObject();
        } catch (IOException e) {
            return null;
        }
    }
    // end region

    protected void promptCompileInfo() throws AzureExecutionException {
        Log.info(String.format("Java home : %s", System.getenv("JAVA_HOME")));
        Log.info(String.format("Artifact compile version : %s", Utils.getArtifactCompileVersion(getArtifactFile())));
    }

    private File getArtifactFile() throws AzureExecutionException {
        final Artifact artifact = project.getArtifact();
        if (artifact.getFile() != null) {
            return artifact.getFile();
        }
        // Get artifact by buildDirectory and finalName
        // as project.getArtifact() will be null when invoke azure-functions:package directly
        final String finalName = project.getBuild().getFinalName();
        final String packaging = project.getPackaging();
        final File result = new File(buildDirectory, StringUtils.join(finalName, FilenameUtils.EXTENSION_SEPARATOR, packaging));
        if (!result.exists()) {
            throw new AzureExecutionException(CAN_NOT_FIND_ARTIFACT);
        }
        return result;
    }

    protected void trackFunctionProperties(Map<String, FunctionConfiguration> configMap) {
        final List<String> bindingTypeSet = configMap.values().stream().flatMap(configuration -> configuration.getBindings().stream())
                .map(Binding::getType)
                .sorted()
                .distinct()
                .collect(Collectors.toList());
        getTelemetryProxy().addDefaultProperty(TRIGGER_TYPE, StringUtils.join(bindingTypeSet, ","));
    }
}
