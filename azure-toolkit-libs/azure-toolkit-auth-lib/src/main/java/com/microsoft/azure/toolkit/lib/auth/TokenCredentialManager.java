/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.SimpleTokenCache;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.policy.FixedDelay;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.util.logging.ClientLogger;
import com.azure.identity.implementation.util.ScopeUtil;
import com.azure.resourcemanager.resources.ResourceManager;
import com.azure.resourcemanager.resources.models.Tenant;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.common.model.AbstractAzServiceSubscription;
import com.microsoft.azure.toolkit.lib.common.model.Subscription;
import com.microsoft.azure.toolkit.lib.common.utils.Utils;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class TokenCredentialManager implements TenantProvider, SubscriptionProvider {
    private static final ClientLogger LOGGER = new ClientLogger(TokenCredentialManager.class);

    @Setter
    @Getter
    protected AzureEnvironment environment;

    @Setter
    @Getter
    protected String email;

    @Setter
    protected Supplier<TokenCredential> rootCredentialSupplier;

    @Setter
    protected Function<String, TokenCredential> credentialSupplier;
    // cache for different tenants
    private final Map<String, TokenCredential> tokenCredentialCache = new ConcurrentHashMap<>();

    public TokenCredential createTokenCredentialForTenant(String tenantId) {
        return this.tokenCredentialCache.computeIfAbsent(tenantId,
            key -> new AutoRefreshableTokenCredential(credentialSupplier.apply(tenantId)));
    }

    static class AutoRefreshableTokenCredential implements TokenCredential {
        // cache for different resources on the same tenant
        private final Map<String, SimpleTokenCache> tokenCache = new ConcurrentHashMap<>();

        private final TokenCredential tokenCredential;

        public AutoRefreshableTokenCredential(TokenCredential tokenCredential) {
            this.tokenCredential = tokenCredential;
        }

        @Override
        public Mono<AccessToken> getToken(TokenRequestContext request) {
            String resource = ScopeUtil.scopesToResource(request.getScopes());
            return tokenCache.computeIfAbsent(resource, (ignore) ->
                new SimpleTokenCache(() -> tokenCredential.getToken(request))).getToken();
        }
    }
}
