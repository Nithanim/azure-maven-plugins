/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.legacy.function.bindings;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

@JsonSerialize(using = BindingSerializer.class)
public class Binding {

    protected BindingEnum bindingEnum = null;

    protected String type = null;

    protected BindingEnum.Direction direction = null;

    protected Map<String, Object> bindingAttributes = new HashMap<>();

    protected static Map<BindingEnum, List<String>> requiredAttributeMap = new HashMap<>();

    static {
        //initialize required attributes, which will be saved to function.json even if it equals to its default value
        requiredAttributeMap.put(BindingEnum.EventHubTrigger, Collections.singletonList("cardinality"));
        requiredAttributeMap.put(BindingEnum.HttpTrigger, Collections.singletonList("authLevel"));
    }

    public Binding(BindingEnum bindingEnum) {
        this.bindingEnum = bindingEnum;
        this.type = bindingEnum.getType();
        this.direction = bindingEnum.getDirection();
    }

    @Deprecated
    public Binding(IndexView index, BindingEnum bindingEnum, AnnotationInstance annotationInstance) {
        this(bindingEnum);
        final ClassInfo annotation = index.getClassByName(annotationInstance.name());
        try {
            for (final AnnotationValue value : annotationInstance.values()) {
                addProperties(annotation, value);
            }
        } catch (Exception e) {
            throw new RuntimeException("Resolving binding attributes failed", e);
        }
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return (String) bindingAttributes.get("name");
    }

    public String getDirection() {
        if (this.direction != null) {
            return direction.toString();
        }

        throw new RuntimeException("Direction must be provided.");
    }

    public BindingEnum getBindingEnum() {
        return bindingEnum;
    }

    public Object getAttribute(String attributeName) {
        return bindingAttributes.get(attributeName);
    }

    public Map<String, Object> getBindingAttributes() {
        return bindingAttributes;
    }

    public void setName(String name) {
        this.bindingAttributes.put("name", name);
    }

    public void setAttribute(String attributeName, Object attributeValue) {
        this.bindingAttributes.put(attributeName, attributeValue);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("[ name: ")
                .append(getName())
                .append(", type: ")
                .append(getType())
                .append(", direction: ")
                .append(getDirection())
                .append(" ]")
                .toString();
    }

    protected void addProperties(ClassInfo annotation, AnnotationValue annotationValue) {
        final String propertyName = annotationValue.name();
        if (propertyName.equals("direction") && annotationValue.kind() == AnnotationValue.Kind.STRING) {
            this.direction = BindingEnum.Direction.fromString(annotationValue.asString());
            return;
        }

        if (propertyName.equals("type") && annotationValue.kind() == AnnotationValue.Kind.STRING) {
            this.type = annotationValue.asString();
            return;
        }

        Object defaultValue = Optional.ofNullable(annotation.method(annotationValue.name()))
                .map(MethodInfo::defaultValue)
                .map(AnnotationValue::value)
                .orElse(null);

        if (!annotationValue.value().equals(defaultValue) ||
                (requiredAttributeMap.get(bindingEnum) != null &&
                        requiredAttributeMap.get(bindingEnum).contains(propertyName))) {
            bindingAttributes.put(propertyName, annotationValue.value());
        }

    }
}
