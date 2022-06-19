/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.legacy.function.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import com.microsoft.azure.toolkit.lib.appservice.function.core.FunctionAnnotation;
import com.microsoft.azure.toolkit.lib.appservice.function.core.FunctionMethod;
import com.microsoft.azure.toolkit.lib.appservice.function.impl.DefaultFunctionProject;
import com.microsoft.azure.toolkit.lib.common.exception.AzureExecutionException;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.legacy.function.bindings.Binding;
import com.microsoft.azure.toolkit.lib.legacy.function.bindings.BindingEnum;
import com.microsoft.azure.toolkit.lib.legacy.function.bindings.BindingFactory;
import com.microsoft.azure.toolkit.lib.legacy.function.configurations.FunctionConfiguration;
import com.microsoft.azure.toolkit.lib.legacy.function.configurations.Retry;

import lombok.extern.slf4j.Slf4j;

import static com.microsoft.azure.toolkit.lib.appservice.function.core.AzureFunctionsAnnotationConstants.EXPONENTIAL_BACKOFF_RETRY;
import static com.microsoft.azure.toolkit.lib.appservice.function.core.AzureFunctionsAnnotationConstants.FIXED_DELAY_RETRY;
import static com.microsoft.azure.toolkit.lib.appservice.function.core.AzureFunctionsAnnotationConstants.FUNCTION_NAME;
import static com.microsoft.azure.toolkit.lib.appservice.function.core.AzureFunctionsAnnotationConstants.STORAGE_ACCOUNT;

@Slf4j
@Deprecated
public class AnnotationHandlerImpl implements AnnotationHandler {

    private static final String MULTI_RETRY_ANNOTATION = "Fixed delay retry and exponential backoff retry are not compatible, " +
        "please use either of them for one trigger";

    @Override
    public Set<MethodInfo> findFunctions(IndexView index) {
        HashSet<MethodInfo> methodInfos = new HashSet<>();
        Collection<AnnotationInstance> annotationInstances = index.getAnnotations(DotName.createSimple(FUNCTION_NAME));
        for (AnnotationInstance annotationInstance : annotationInstances) {
            if (annotationInstance.target().kind() == AnnotationTarget.Kind.METHOD) {
                methodInfos.add(annotationInstance.target().asMethod());
            }
        }
        return methodInfos;
    }

    public IndexView buildIndex(final List<URL> urls) {
        try {
            List<IndexView> indexes = new ArrayList<>();
            for (URL url : urls) {
                InputStream persistedIndexStream = getClassLoader(url).getResourceAsStream("/META-INF/jandex.idx");
                if (persistedIndexStream != null) {
                    indexes.add(new IndexReader(persistedIndexStream).read());
                } else {
                    if (url.getProtocol().equals("file") && url.getFile().endsWith("/")) {
                        indexes.add(indexLocalFolder(url));
                    }
                }
            }
            return CompositeIndex.create(indexes);
        } catch (IOException | URISyntaxException e) {
            throw new AzureToolkitRuntimeException(e);
        }
    }

    private IndexView indexLocalFolder(URL url) throws URISyntaxException, IOException {
        Indexer indexer = new Indexer();
        Path p = Paths.get(url.toURI());

        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".class")) {
                    indexer.index(Files.newInputStream(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return indexer.complete();
    }

    protected ClassLoader getClassLoader(final URL url) {
        return new URLClassLoader(new URL[]{url}, null);
    }

    @Override
    public Map<String, FunctionConfiguration> generateConfigurations(final IndexView index, final Set<MethodInfo> methods) throws AzureExecutionException {
        final Map<String, FunctionConfiguration> configMap = new HashMap<>();
        for (final MethodInfo method : methods) {
            final FunctionMethod functionMethod = DefaultFunctionProject.create(index, method);
            final FunctionAnnotation functionNameAnnotation = functionMethod.getAnnotation(FUNCTION_NAME);
            if (functionNameAnnotation == null) {
                continue;
            }
            final String functionName = functionNameAnnotation.getStringValue("value", true);
            validateFunctionName(configMap.keySet(), functionName);
            log.debug("Starting processing function : " + functionName);
            configMap.put(functionName, generateConfiguration(index, method));
        }
        return configMap;
    }

    protected void validateFunctionName(final Set<String> nameSet, final String functionName) throws AzureExecutionException {
        if (StringUtils.isEmpty(functionName)) {
            throw new AzureExecutionException("Azure Functions name cannot be empty.");
        }
        if (nameSet.stream().anyMatch(n -> StringUtils.equalsIgnoreCase(n, functionName))) {
            throw new AzureExecutionException("Found duplicate Azure Function: " + functionName);
        }
    }

    @Override
    public FunctionConfiguration generateConfiguration(final IndexView index, final MethodInfo method) throws AzureExecutionException {
        final FunctionConfiguration config = new FunctionConfiguration();
        final List<Binding> bindings = config.getBindings();

        processParameterAnnotations(index, method, bindings);

        processMethodAnnotations(index, method, bindings);

        patchStorageBinding(index, method, bindings);

        config.setRetry(getRetryConfigurationFromMethod(index, method));
        config.setEntryPoint(method.declaringClass().name() + "." + method.name());
        return config;
    }

    private Retry getRetryConfigurationFromMethod(final IndexView index, MethodInfo method) throws AzureExecutionException {
        final FunctionMethod functionMethod = DefaultFunctionProject.create(index, method);
        final FunctionAnnotation fixedDelayRetry = functionMethod.getAnnotation(FIXED_DELAY_RETRY);
        final FunctionAnnotation exponentialBackoffRetry = functionMethod.getAnnotation(EXPONENTIAL_BACKOFF_RETRY);
        if (fixedDelayRetry != null && exponentialBackoffRetry != null) {
            throw new AzureExecutionException(MULTI_RETRY_ANNOTATION);
        }
        if (fixedDelayRetry != null) {
            return Retry.createFixedDelayRetryFromAnnotation(fixedDelayRetry);
        }
        if (exponentialBackoffRetry != null) {
            return Retry.createExponentialBackoffRetryFromAnnotation(exponentialBackoffRetry);
        }
        return null;
    }

    protected void processParameterAnnotations(final IndexView index, final MethodInfo method, final List<Binding> bindings) {
        for (final AnnotationInstance annotation : method.annotations()) {
            if (annotation.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
                bindings.addAll(
                        parseAnnotations(() -> Collections.singletonList(annotation), a -> parseParameterAnnotation(index, a)));
            }
        }
    }

    protected void processMethodAnnotations(final IndexView index, final MethodInfo method, final List<Binding> bindings) {
        if (method.returnType().kind() != Type.Kind.VOID) {
            List<AnnotationInstance> methodAnnotations = method.annotations().stream()
                    .filter(a -> a.target().kind() == AnnotationTarget.Kind.METHOD)
                    .collect(Collectors.toList());
            bindings.addAll(parseAnnotations(() -> methodAnnotations, a -> parseMethodAnnotation(index, a)));

            if (bindings.stream().anyMatch(b -> b.getBindingEnum() == BindingEnum.HttpTrigger) &&
                bindings.stream().noneMatch(b -> b.getName().equalsIgnoreCase("$return"))) {
                bindings.add(BindingFactory.getHTTPOutBinding());
            }
        }
    }

    protected List<Binding> parseAnnotations(Supplier<List<AnnotationInstance>> annotationProvider,
                                             Function<AnnotationInstance, Binding> annotationParser) {
        final List<Binding> bindings = new ArrayList<>();

        for (final AnnotationInstance annotation : annotationProvider.get()) {
            final Binding binding = annotationParser.apply(annotation);
            if (binding != null) {
                log.debug("Adding binding: " + binding);
                bindings.add(binding);
            }
        }

        return bindings;
    }

    protected Binding parseParameterAnnotation(final IndexView index, final AnnotationInstance annotation) {
        return BindingFactory.getBinding(index, annotation);
    }

    protected Binding parseMethodAnnotation(final IndexView index, final AnnotationInstance annotation) {
        final Binding ret = parseParameterAnnotation(index, annotation);
        if (ret != null) {
            ret.setName("$return");
        }
        return ret;
    }

    protected void patchStorageBinding(final IndexView index, final MethodInfo method, final List<Binding> bindings) {
        final FunctionMethod functionMethod = DefaultFunctionProject.create(index, method);
        final FunctionAnnotation storageAccount = functionMethod.getAnnotation(STORAGE_ACCOUNT);

        if (storageAccount != null) {
            log.debug("StorageAccount annotation found.");
            final String connectionString = storageAccount.getStringValue("value", true);
            // Replace empty connection string
            bindings.stream().filter(binding -> binding.getBindingEnum().isStorage())
                .filter(binding -> StringUtils.isEmpty((String) binding.getAttribute("connection")))
                .forEach(binding -> binding.setAttribute("connection", connectionString));
        } else {
            log.debug("No StorageAccount annotation found.");
        }
    }
}
