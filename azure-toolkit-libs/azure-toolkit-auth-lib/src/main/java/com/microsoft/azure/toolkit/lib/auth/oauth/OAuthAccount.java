/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth.oauth;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.identity.InteractiveBrowserCredentialBuilder;
import com.azure.identity.TokenCachePersistenceOptions;
import com.azure.identity.implementation.util.IdentityConstants;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.auth.Account;
import com.microsoft.azure.toolkit.lib.auth.AzureCloud;
import com.microsoft.azure.toolkit.lib.auth.RefreshTokenTokenCredentialManager;
import com.microsoft.azure.toolkit.lib.auth.TokenCredentialManager;
import com.microsoft.azure.toolkit.lib.auth.exception.AzureToolkitAuthenticationException;
import com.microsoft.azure.toolkit.lib.auth.AuthType;
import com.microsoft.azure.toolkit.lib.auth.util.AzureEnvironmentUtils;
import me.alexpanov.net.FreePortFinder;
import reactor.core.publisher.Mono;

import java.awt.*;

public class OAuthAccount extends Account {
    public OAuthAccount() {
        super(AuthType.OAUTH2, IdentityConstants.DEVELOPER_SINGLE_SIGN_ON_ID);
    }

    @Override
    public Mono<Boolean> checkApplicable() {
        return Mono.fromCallable(() -> {
            if (!isBrowserAvailable()) {
                throw new AzureToolkitAuthenticationException("browser is not available for oauth login.");
            }
            return true;
        });
    }

    protected Mono<TokenCredentialManager> createTokenCredentialManager() {
        AzureEnvironment env = Azure.az(AzureCloud.class).getOrDefault();
        return RefreshTokenTokenCredentialManager.createTokenCredentialManager(env, getClientId(), createCredential(env));
    }

    protected TokenCredential createCredential(AzureEnvironment env) {
        AzureEnvironmentUtils.setupAzureEnvironment(env);
        InteractiveBrowserCredentialBuilder builder = new InteractiveBrowserCredentialBuilder();
        if (isPersistenceEnabled()) {
            builder.tokenCachePersistenceOptions(new TokenCachePersistenceOptions().setName(TOOLKIT_TOKEN_CACHE_NAME));
        }
        return builder.redirectUrl("http://localhost:" + FreePortFinder.findFreeLocalPort())
            .build();
    }

    private static boolean isBrowserAvailable() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
    }
}
