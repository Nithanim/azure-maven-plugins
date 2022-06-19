/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.legacy.function.bindings;


import com.microsoft.azure.toolkit.lib.appservice.function.core.FunctionAnnotation;
import com.microsoft.azure.toolkit.lib.appservice.function.impl.DefaultFunctionProject;

import java.lang.annotation.Annotation;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;

public class ExtendedCustomBinding extends Binding {

    private final FunctionAnnotation customBindingAnnotation;

    public ExtendedCustomBinding(IndexView index,
                                 BindingEnum bindingEnum,
                                 AnnotationInstance customBindingAnnotation,
                                 AnnotationInstance annotation) {
        super(index, bindingEnum, annotation);
        this.customBindingAnnotation = DefaultFunctionProject.create(index, customBindingAnnotation);
    }

    @Override
    public String getName() {
        final String name = super.getName();
        if (name != null) {
            return name;
        }
        return customBindingAnnotation.getStringValue("name", true);
    }

    @Override
    public String getDirection() {
        if (this.direction != null) {
            return direction.toString();
        }
        return customBindingAnnotation.getStringValue("direction", true);
    }

    @Override
    public String getType() {
        if (type != null) {
            return type;
        }
        return customBindingAnnotation.getStringValue("type", true);
    }
}
