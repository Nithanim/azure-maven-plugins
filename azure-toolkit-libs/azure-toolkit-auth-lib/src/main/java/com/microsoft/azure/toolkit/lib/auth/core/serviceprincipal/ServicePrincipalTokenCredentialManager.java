/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth.core.serviceprincipal;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.microsoft.azure.toolkit.lib.auth.TokenCredentialManager;

import javax.annotation.Nonnull;

class ServicePrincipalTokenCredentialManager extends TokenCredentialManager {

    public ServicePrincipalTokenCredentialManager(@Nonnull AzureEnvironment env, @Nonnull TokenCredential credential) {
        this.environment = env;
        this.rootCredentialSupplier = () -> credential;
        this.credentialSupplier = tenant -> credential;
    }
}
