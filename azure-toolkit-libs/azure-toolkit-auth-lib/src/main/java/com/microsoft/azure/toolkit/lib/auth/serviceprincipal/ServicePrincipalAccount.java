/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth.serviceprincipal;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.identity.ClientCertificateCredentialBuilder;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.auth.Account;
import com.microsoft.azure.toolkit.lib.auth.AuthType;
import com.microsoft.azure.toolkit.lib.auth.AzureCloud;
import com.microsoft.azure.toolkit.lib.auth.TokenCredentialManager;
import com.microsoft.azure.toolkit.lib.auth.exception.AzureToolkitAuthenticationException;
import com.microsoft.azure.toolkit.lib.auth.exception.InvalidConfigurationException;
import com.microsoft.azure.toolkit.lib.auth.util.AzureEnvironmentUtils;
import com.microsoft.azure.toolkit.lib.auth.util.ValidationUtil;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.Objects;

public class ServicePrincipalAccount extends Account {
    private final ServicePrincipalAuthConfiguration config;

    public ServicePrincipalAccount(@Nonnull ServicePrincipalAuthConfiguration config) {
        super(AuthType.SERVICE_PRINCIPAL, config.getClient());
        Objects.requireNonNull(config);
        this.config = config;
    }

    public Mono<Boolean> checkApplicable() {
        return Mono.fromCallable(() -> {
            try {
                ValidationUtil.validateAuthConfiguration(config);
                return true;
            } catch (InvalidConfigurationException e) {
                throw new AzureToolkitAuthenticationException(
                    "Cannot login through 'SERVICE_PRINCIPAL' due to invalid configuration:" + e.getMessage());
            }
        });
    }

    protected Mono<TokenCredentialManager> createTokenCredentialManager() {
        AzureEnvironment env = ObjectUtils.firstNonNull(config.getEnvironment(), Azure.az(AzureCloud.class).getOrDefault());
        return Mono.just(new ServicePrincipalTokenCredentialManager(env, createCredential(env)));
    }

    private TokenCredential createCredential(AzureEnvironment env) {
        AzureEnvironmentUtils.setupAzureEnvironment(env);
        return StringUtils.isNotBlank(config.getCertificate()) ?
            new ClientCertificateCredentialBuilder().clientId(config.getClient())
                .pfxCertificate(config.getCertificate(), config.getCertificatePassword())
                .tenantId(config.getTenant()).build()
            : new ClientSecretCredentialBuilder().clientId(config.getClient())
            .clientSecret(config.getKey()).tenantId(config.getTenant()).build();
    }
}
