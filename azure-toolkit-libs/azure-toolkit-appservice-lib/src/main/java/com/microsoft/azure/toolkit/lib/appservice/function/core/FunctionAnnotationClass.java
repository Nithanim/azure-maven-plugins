/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */
package com.microsoft.azure.toolkit.lib.appservice.function.core;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

import org.jboss.jandex.DotName;

public class FunctionAnnotationClass implements IAnnotatable {
    @Setter
    @Getter
    private DotName fullName;

    @Setter
    @Getter
    private String name;

    @Setter
    @Getter
    private List<FunctionAnnotation> annotations;

    @Override
    public String toString() {
        return fullName.toString();
    }
}
