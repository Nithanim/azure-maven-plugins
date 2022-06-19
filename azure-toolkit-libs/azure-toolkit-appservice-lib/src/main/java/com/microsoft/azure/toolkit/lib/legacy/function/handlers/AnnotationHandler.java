/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.legacy.function.handlers;

import com.microsoft.azure.toolkit.lib.common.exception.AzureExecutionException;
import com.microsoft.azure.toolkit.lib.legacy.function.configurations.FunctionConfiguration;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

public interface AnnotationHandler {
    IndexView buildIndex(final List<URL> urls);

    Set<MethodInfo> findFunctions(final IndexView index);

    Map<String, FunctionConfiguration> generateConfigurations(final IndexView index, final Set<MethodInfo> methods) throws AzureExecutionException;

    FunctionConfiguration generateConfiguration(final IndexView index, final MethodInfo method) throws AzureExecutionException;
}
