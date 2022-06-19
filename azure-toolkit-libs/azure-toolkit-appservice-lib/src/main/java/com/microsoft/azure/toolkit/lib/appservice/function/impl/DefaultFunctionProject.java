/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */
package com.microsoft.azure.toolkit.lib.appservice.function.impl;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import com.microsoft.azure.toolkit.lib.appservice.function.core.FunctionAnnotation;
import com.microsoft.azure.toolkit.lib.appservice.function.core.FunctionAnnotationClass;
import com.microsoft.azure.toolkit.lib.appservice.function.core.FunctionMethod;
import com.microsoft.azure.toolkit.lib.appservice.function.core.FunctionProject;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.CommandHandler;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.CommandHandlerImpl;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.FunctionCoreToolsHandler;
import com.microsoft.azure.toolkit.lib.legacy.function.handlers.FunctionCoreToolsHandlerImpl;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import static com.microsoft.azure.toolkit.lib.appservice.function.core.AzureFunctionsAnnotationConstants.FUNCTION_NAME;

@Slf4j
public class DefaultFunctionProject extends FunctionProject {

    @Override
    public List<FunctionMethod> findAnnotatedMethods() {
        try {
            Set<MethodInfo> methods;
            IndexView index;
            try {
                log.debug("ClassPath to resolve: " + getTargetClassUrl());
                final List<URL> dependencyWithTargetClass = getDependencyArtifactUrls();
                dependencyWithTargetClass.add(getTargetClassUrl());
                index = buildIndex(dependencyWithTargetClass);
                methods = findFunctions(index);
            } catch (NoClassDefFoundError e) {
                // fallback to reflect through artifact url, for shaded project(fat jar)
                log.debug("ClassPath to resolve: " + getArtifactUrl());
                index = buildIndex(Collections.singletonList(getArtifactUrl()));
                methods = findFunctions(index);
            }
            IndexView finalIndex = index;
            return methods.stream().map(m -> create(finalIndex, m)).collect(Collectors.toList());
        } catch (MalformedURLException e) {
            throw new AzureToolkitRuntimeException("Invalid URL when resolving functions in class path:" + e.getMessage(), e);
        }
    }

    @SneakyThrows
    @Override
    public void installExtension(String funcPath) {
        final CommandHandler commandHandler = new CommandHandlerImpl();
        final FunctionCoreToolsHandler functionCoreToolsHandler = getFunctionCoreToolsHandler(commandHandler);
        functionCoreToolsHandler.installExtension(getStagingFolder(),
            getBaseDirectory());
    }

    private static FunctionCoreToolsHandler getFunctionCoreToolsHandler(final CommandHandler commandHandler) {
        return new FunctionCoreToolsHandlerImpl(commandHandler);
    }

    private URL getTargetClassUrl() throws MalformedURLException {
        return getClassesOutputDirectory().toURI().toURL();
    }

    /**
     * @return URLs for the classpath with compile scope needed jars
     */
    private List<URL> getDependencyArtifactUrls() {
        final List<URL> urlList = new ArrayList<>();
        getDependencies().forEach(file -> {
            try {
                urlList.add(file.toURI().toURL());
            } catch (MalformedURLException e) {
                log.debug("Failed to get URL for file: " + file);
            }
        });
        return urlList;
    }

    private static IndexView buildIndex(final List<URL> urls) {
        try {
            List<IndexView> indexes = new ArrayList<>();
            for (URL url : urls) {
                InputStream persistedIndexStream = getClassLoader(url).getResourceAsStream("/META-INF/jandex.idx");
                if (persistedIndexStream != null) {
                    indexes.add(new IndexReader(persistedIndexStream).read());
                }
            }
            return CompositeIndex.create(indexes);
        } catch (IOException e) {
            throw new AzureToolkitRuntimeException(e);
        }
    }

    private static Set<MethodInfo> findFunctions(final IndexView index) {
        HashSet<MethodInfo> methodInfos = new HashSet<>();
        Collection<AnnotationInstance> annotationInstances = index.getAnnotations(DotName.createSimple(FUNCTION_NAME));
        for (AnnotationInstance annotationInstance : annotationInstances) {
            if (annotationInstance.target().kind() == AnnotationTarget.Kind.METHOD) {
                methodInfos.add(annotationInstance.target().asMethod());
            }
        }
        return methodInfos;
    }

    private static ClassLoader getClassLoader(final URL url) {
        return new URLClassLoader(new URL[]{url}, null);
    }

    private URL getArtifactUrl() throws MalformedURLException {
        return getArtifactFile().toURI().toURL();
    }

    public static FunctionAnnotation create(@Nonnull IndexView index, @Nonnull AnnotationInstance annotation) {
        return create(index, annotation, true);
    }

    public static FunctionMethod create(IndexView index, MethodInfo method) {
        FunctionMethod functionMethod = new FunctionMethod();
        functionMethod.setName(method.name());
        functionMethod.setReturnTypeName(method.returnType().name());
        functionMethod.setAnnotations(
                method.annotations().stream()
                        .filter(a -> a.target().kind() == AnnotationTarget.Kind.METHOD)
                        .map(a -> create(index, a)).collect(Collectors.toList()));

        Map<Short, List<AnnotationInstance>> parameterAnnotationsByPosition = method.annotations().stream()
                .filter(a -> a.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER)
                .collect(Collectors.groupingBy(a -> a.target().asMethodParameter().position()));
        List<FunctionAnnotation[]> parameterAnnotations = new ArrayList<>();
        for (short i = 0; i < method.parameters().size(); i++) {
            parameterAnnotations.add(parameterAnnotationsByPosition
                    .getOrDefault(i, Collections.emptyList())
                    .stream()
                    .map(a -> create(index, a))
                    .toArray(FunctionAnnotation[]::new));
        }

        functionMethod.setParameterAnnotations(parameterAnnotations);
        functionMethod.setDeclaringTypeName(method.declaringClass().name());
        return functionMethod;
    }

    private static FunctionAnnotation create(@Nonnull IndexView index, @Nonnull AnnotationInstance annotation, boolean resolveAnnotationType) {
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> defaultMap = new HashMap<>();
        ClassInfo annotationClass = index.getClassByName(annotation.name());
        if (annotationClass == null) {
            throw new IllegalStateException("Annotation class no indexed: " + annotation.name());
        }
        for (MethodInfo method : annotationClass.methods()) {
            Object defaultValue = Optional.ofNullable(method.defaultValue())
                    .map(AnnotationValue::value)
                    .orElse(null);
            AnnotationValue actualValueHolder = annotation.value(method.name());
            if (actualValueHolder == null) {
                defaultMap.put(method.name(), defaultValue);
            } else {
                map.put(method.name(), actualValueHolder.value());
            }
        }

        FunctionAnnotation functionAnnotation = new FunctionAnnotation() {
            public boolean isAnnotationType(@Nonnull Class<? extends Annotation> clz) {
                return clz.isInstance(annotation);
            }
        };
        if (resolveAnnotationType) {
            functionAnnotation.setAnnotationClass(toFunctionAnnotationClass(index, annotationClass));
        }
        functionAnnotation.setProperties(map);
        functionAnnotation.setDefaultProperties(defaultMap);
        return functionAnnotation;
    }

    private static FunctionAnnotationClass toFunctionAnnotationClass(IndexView index, ClassInfo clz) {
        FunctionAnnotationClass type = new FunctionAnnotationClass();
        type.setFullName(clz.name());
        type.setName(clz.name().local());
        type.setAnnotations(clz.annotations().values().stream()
                .flatMap(Collection::stream)
                .map(a -> create(index, a, false))
                .collect(Collectors.toList()));
        return type;
    }
}
