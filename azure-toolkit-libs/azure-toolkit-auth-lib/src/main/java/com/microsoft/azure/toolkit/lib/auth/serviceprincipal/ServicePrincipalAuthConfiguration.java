/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth.serviceprincipal;

import com.microsoft.azure.toolkit.lib.auth.AuthConfiguration;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ServicePrincipalAuthConfiguration extends AuthConfiguration {
    private String key;
    private String certificate;
    private String certificatePassword;
}
