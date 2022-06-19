/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */
package com.microsoft.azure.toolkit.lib.appservice.function.core;

import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FunctionAnnotation {
    @Setter
    @Getter
    private FunctionAnnotationClass annotationClass;

    @Setter
    private Map<String, Object> properties;
    @Setter
    private Map<String, Object> defaultProperties;

    public Map<String, Object> getAnnotationProperties(boolean includeDefaultValue) {
        if (!includeDefaultValue) {
            return properties;
        }
        Map<String, Object> res = new HashMap<>(properties);
        defaultProperties.forEach(res::putIfAbsent);
        return res;
    }

    public Map<String, Object> getDeclaredAnnotationProperties() {
        return getAnnotationProperties(false);
    }

    public Map<String, Object> getAllAnnotationProperties() {
        return getAnnotationProperties(true);
    }

    public Map<String, Object> getPropertiesWithRequiredProperties(List<String> requiredProperties) {
        final Map<String, Object> copiedMap = new HashMap<>(properties);
        if (requiredProperties != null) {
            requiredProperties.forEach(key -> {
                if (defaultProperties.containsKey(key)) {
                    copiedMap.putIfAbsent(key, defaultProperties.get(key));
                }
            });
        }
        return copiedMap;
    }

    public boolean isAnnotationType(@Nonnull ClassInfo annotationClass) {
        return Objects.equals(getAnnotationClassName(), annotationClass.name());
    }

    public boolean isAnnotationType(@Nonnull final String className) {
        return StringUtils.equals(getAnnotationClassName().toString(), className);
    }

    public Object get(String key, boolean includeDefaultValue) {
        if (includeDefaultValue) {
            return properties.getOrDefault(key, defaultProperties.get(key));
        } else {
            return properties.get(key);
        }
    }

    public String getStringValue(String key, boolean includeDefaultValue) {
        Object value = get(key, includeDefaultValue);
        if (value != null && !(value instanceof String)) {
            throw new AzureToolkitRuntimeException(String.format("Unexpected key '%s' with type '%s'", key, value.getClass().getSimpleName()));
        }
        return value == null ? null : (String) value;
    }

    public DotName getAnnotationClassName() {
        return annotationClass.getFullName();
    }
}
