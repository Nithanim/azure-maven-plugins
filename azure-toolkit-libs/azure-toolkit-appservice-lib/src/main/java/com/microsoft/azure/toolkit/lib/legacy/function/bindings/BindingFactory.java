/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.legacy.function.bindings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.IndexView;

import static com.microsoft.azure.toolkit.lib.appservice.function.core.AzureFunctionsAnnotationConstants.CUSTOM_BINDING;

@Deprecated
public class BindingFactory {
    private static final String HTTP_OUTPUT_DEFAULT_NAME = "$return";

    public static Binding getBinding(final IndexView index, final AnnotationInstance annotationInstance) {
        final BindingEnum annotationEnum = Arrays.stream(BindingEnum.values())
            .filter(bindingEnum -> bindingEnum.name().toLowerCase(Locale.ENGLISH)
                .equals(annotationInstance.name().local().toLowerCase(Locale.ENGLISH)))
            .findFirst().orElse(null);
        return annotationEnum == null ? getUserDefinedBinding(index, annotationInstance) : new Binding(index, annotationEnum, annotationInstance);
    }

    public static Binding getUserDefinedBinding(final IndexView index, final AnnotationInstance annotation) {
        return index.getClassByName(annotation.name()).annotations().values().stream()
                .flatMap(List::stream)
                .filter(declaredAnnotation -> StringUtils.equals(declaredAnnotation.name().toString(), CUSTOM_BINDING))
                .findFirst()
                .map(customBindingAnnotation -> new ExtendedCustomBinding(index, BindingEnum.ExtendedCustomBinding,
                        customBindingAnnotation, annotation))
                .orElse(null);
    }

    public static Binding getHTTPOutBinding() {
        final Binding result = new Binding(BindingEnum.HttpOutput);
        result.setName(HTTP_OUTPUT_DEFAULT_NAME);
        return result;
    }
}
