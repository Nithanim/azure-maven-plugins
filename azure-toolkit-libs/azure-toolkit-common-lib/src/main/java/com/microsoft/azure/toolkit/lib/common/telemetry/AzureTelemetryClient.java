/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.common.telemetry;

import com.microsoft.applicationinsights.TelemetryClient;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.azure.toolkit.lib.common.action.Action.RESOURCE_TYPE;

@Getter
public class AzureTelemetryClient {
    public static final String ARCH_KEY = "arch";
    public static final String JDK_KEY = "jdk";

    private static final String[] SYSTEM_PROPERTIES = new String[]{RESOURCE_TYPE};
    // refers https://github.com/microsoft/vscode-extension-telemetry/blob/main/src/telemetryReporter.ts
    private static final String FILE_PATH_REGEX =
            "(file://)?([a-zA-Z]:(\\\\\\\\|\\\\|/)|(\\\\\\\\|\\\\|/))?([\\w-._]+(\\\\\\\\|\\\\|/))+[\\w-._]*";
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(FILE_PATH_REGEX);
    // refers https://stackoverflow.com/questions/201323/how-can-i-validate-an-email-address-using-a-regular-expression
    private static final String EMAIL_REGEX = "(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"" +
            "(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@" +
            "(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}" +
            "(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:" +
            "(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)])";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL_REGEX);

    private final TelemetryClient client;
    @Setter
    private Map<String, String> defaultProperties;
    private boolean isEnabled = true;     // Telemetry is enabled by default.

    public AzureTelemetryClient() {
        this(Collections.emptyMap());
    }

    public AzureTelemetryClient(@Nonnull final Map<String, String> defaultProperties) {
        this.client = new TelemetryClient();
        this.defaultProperties = new HashMap<>();
        initDefaultProperties();
        this.defaultProperties.putAll(defaultProperties);
    }

    public void addDefaultProperty(@Nonnull String key, @Nonnull String value) {
        if (StringUtils.isEmpty(key)) {
            return;
        }
        defaultProperties.put(key, value);
    }

    public void enable() {
        this.isEnabled = true;
    }

    public void disable() {
        this.isEnabled = false;
    }

    public void trackEvent(@Nonnull final String eventName) {
        trackEvent(eventName, null, null, false);
    }

    public void trackEvent(@Nonnull final String eventName, @Nullable final Map<String, String> customProperties) {
        trackEvent(eventName, customProperties, null, false);
    }

    public void trackEvent(@Nonnull final String eventName, @Nullable final Map<String, String> customProperties, @Nullable final Map<String, Double> metrics) {
        trackEvent(eventName, customProperties, metrics, false);
    }

    public void trackEvent(@Nonnull final String eventName, @Nullable final Map<String, String> customProperties, @Nullable final Map<String, Double> metrics,
                           final boolean overrideDefaultProperties) {
        if (!isEnabled()) {
            return;
        }

        final Map<String, String> properties = mergeProperties(getDefaultProperties(), customProperties, overrideDefaultProperties);
        properties.entrySet().removeIf(stringStringEntry -> StringUtils.isEmpty(stringStringEntry.getValue())); // filter out null values
        anonymizePersonallyIdentifiableInformation(properties);
        client.trackEvent(eventName, properties, metrics);
        client.flush();
    }

    protected Map<String, String> mergeProperties(Map<String, String> defaultProperties,
                                                  Map<String, String> customProperties,
                                                  boolean overrideDefaultProperties) {
        if (customProperties == null) {
            return defaultProperties;
        }
        final Map<String, String> merged = new HashMap<>();
        if (overrideDefaultProperties) {
            merged.putAll(defaultProperties);
            merged.putAll(customProperties);
        } else {
            merged.putAll(customProperties);
            merged.putAll(defaultProperties);
        }
        return merged;
    }

    private void initDefaultProperties() {
        this.addDefaultProperty(ARCH_KEY, System.getProperty("os.arch"));
        this.addDefaultProperty(JDK_KEY, System.getProperty("java.version"));
    }

    private void anonymizePersonallyIdentifiableInformation(final Map<String, String> properties) {
        properties.replaceAll((key, value) -> {
            if (StringUtils.isBlank(value) || StringUtils.equalsAnyIgnoreCase(key, SYSTEM_PROPERTIES)) {
                return value;
            }
            final String input = FILE_PATH_PATTERN.matcher(value).replaceAll("<REDACTED: user-file-path>");
            return EMAIL_PATTERN.matcher(input).replaceAll("<REDACTED: user-email-address>");
        });
    }
}
