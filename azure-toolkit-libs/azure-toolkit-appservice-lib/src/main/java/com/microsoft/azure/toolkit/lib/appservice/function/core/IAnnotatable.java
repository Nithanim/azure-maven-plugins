/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */
package com.microsoft.azure.toolkit.lib.appservice.function.core;

import org.apache.commons.lang3.StringUtils;
import org.jboss.jandex.ClassInfo;

import java.lang.annotation.Annotation;
import java.util.List;

public interface IAnnotatable {
    List<FunctionAnnotation> getAnnotations();

    default FunctionAnnotation getAnnotation(ClassInfo annotationClass) {
        return getAnnotations().stream().filter(annotation -> annotation.isAnnotationType(annotationClass)).findFirst().orElse(null);
    }

    default FunctionAnnotation getAnnotation(String annotationName) {
        return getAnnotations().stream()
                .filter(annotation -> StringUtils.equals(annotation.getAnnotationClassName().toString(), annotationName)).findFirst().orElse(null);
    }
}
