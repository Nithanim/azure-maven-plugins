/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.policy.FixedDelay;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.util.logging.ClientLogger;
import com.azure.resourcemanager.resources.ResourceManager;
import com.azure.resourcemanager.resources.models.Tenant;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.account.IAccount;
import com.microsoft.azure.toolkit.lib.auth.exception.AzureToolkitAuthenticationException;
import com.microsoft.azure.toolkit.lib.auth.util.AzureEnvironmentUtils;
import com.microsoft.azure.toolkit.lib.common.cache.CacheEvict;
import com.microsoft.azure.toolkit.lib.common.cache.Preloader;
import com.microsoft.azure.toolkit.lib.common.event.AzureEventBus;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.model.AbstractAzServiceSubscription;
import com.microsoft.azure.toolkit.lib.common.model.Subscription;
import com.microsoft.azure.toolkit.lib.common.task.AzureTaskManager;
import com.microsoft.azure.toolkit.lib.common.utils.TextUtils;
import com.microsoft.azure.toolkit.lib.common.utils.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Getter
public abstract class Account implements IAccount {
    private static final ClientLogger LOGGER = new ClientLogger(Account.class);
    protected static final String TOOLKIT_TOKEN_CACHE_NAME = "azure-toolkit.cache";

    private final AuthType authType;
    private final String clientId;
    private AzureEnvironment environment;
    private String email;
    @Getter(AccessLevel.NONE)
    private List<Subscription> subscriptions;
    private boolean applicable;
    @Setter(AccessLevel.PACKAGE)
    @Getter(AccessLevel.PROTECTED)
    protected boolean persistenceEnabled = false;
    protected TokenCredentialManager credentialManager;

    public Account(@Nonnull AuthType authType, String clientId) {
        this.authType = authType;
        this.clientId = clientId;
    }

    public TokenCredential getTokenCredentialForTenant(String tenantId) {
        requireAuthenticated();
        if (StringUtils.isBlank(tenantId)) {
            throw new IllegalArgumentException("Should provide non-empty tenant id for retrieving credential.");
        } else {
            return this.credentialManager.createTokenCredentialForTenant(tenantId);
        }
    }

    public TokenCredential getTokenCredential(String subscriptionId) {
        requireAuthenticated();
        Subscription subscription = getSubscription(subscriptionId);
        return getTokenCredentialForTenant(subscription.getTenantId());
    }

    private Mono<TokenCredentialManager> initializeTokenCredentialManager() {
        return createTokenCredentialManager().doOnSuccess(tokenCredentialManager -> this.credentialManager = tokenCredentialManager);
    }

    protected abstract Mono<TokenCredentialManager> createTokenCredentialManager();

    public abstract Mono<Boolean> checkApplicable();

    /***
     * The main part of login process: check available and initialize TokenCredentialManager and list tenant ids
     *
     * @return Mono = true if this account is available
     */
    private Mono<Boolean> loginStep1() {
        // step 1: check avail
        // step 2: create TokenCredentialManager
        // step 3: list tenant using TokenCredentialManager
        // step 4: fill account entity
        return checkApplicable().flatMap(ignore -> initializeTokenCredentialManager()).flatMap(this::loadTenantIdsIfAbsent).doOnSuccess(tenantIds -> {
            if (StringUtils.isNotBlank(credentialManager.getEmail())) {
                this.email = credentialManager.getEmail();
            }
            this.environment = credentialManager.getEnvironment();
        }).map(ignore -> {
            this.applicable = true;
            return true;
        });
    }

    protected Mono<Account> login() {
        Mono<Boolean> mono = loginStep1();
        return mono.flatMap(ignore -> {
            if (CollectionUtils.isEmpty(this.getSubscriptions())) {
                return this.credentialManager.listSubscriptions(this.getTenantIds())
                    .map(subscriptions -> {
                        this.subscriptions = subscriptions;
                        return true;
                    });
            }
            return Mono.just(true);
        }).map(ignore -> {
            finishLogin();
            return this;
        });
    }

    public Mono<Account> continueLogin() {
        Azure.az(AzureAccount.class).setAccount(this);
        return Mono.just(this);
    }

    private void finishLogin() {
        markSubscriptionsSelected(this.getSelectedSubscriptionIds());
        // select all when no subs are selected
        if (this.getSelectedSubscriptions().isEmpty()) {
            this.setSelectedSubscriptions(getSubscriptions().stream().map(Subscription::getId).collect(Collectors.toList()));
        }
    }

    @CacheEvict(CacheEvict.ALL) // evict all caches on signing out
    public void logout() {
        this.applicable = false;
        Azure.az(AzureAccount.class).logout();
    }

    public Mono<List<Subscription>> reloadSubscriptions() {
        final List<String> selectedSubscriptionIds = this.getSelectedSubscriptions().stream().map(Subscription::getId).collect(Collectors.toList());
        return credentialManager.listTenants().flatMap(tenantIds -> this.credentialManager.listSubscriptions(tenantIds)
            .map(subscriptions -> {
                // reset tenant id again when all subscriptions
                this.subscriptions = subscriptions;
                this.setSelectedSubscriptions(selectedSubscriptionIds);
                return this.getSubscriptions();
            }));
    }

    public Mono<List<String>> listTenants() {
        return this.createAzureClient(environment).tenants().listAsync().map(Tenant::tenantId).collectList();
    }

    public Mono<List<Subscription>> listSubscriptions(List<String> tenantIds) {
        return Flux.fromIterable(tenantIds).parallel().runOn(Schedulers.boundedElastic())
            .flatMap(tenant -> listSubscriptionsInTenant(createAzureClient(environment, tenant), tenant)).sequential().collectList()
            .map(subscriptionsSet -> subscriptionsSet.stream()
                .flatMap(Collection::stream)
                .filter(Utils.distinctByKey(subscription -> StringUtils.lowerCase(subscription.getId())))
                .collect(Collectors.toList()));
    }

    private static Mono<List<Subscription>> listSubscriptionsInTenant(ResourceManager.Authenticated client, String tenantId) {
        return client.subscriptions().listAsync().map(Subscription::new).collectList().onErrorResume(ex -> {
            // warn and ignore, should modify here if IMessage is ready
            LOGGER.warning(String.format("Cannot get subscriptions for tenant %s " +
                ", please verify you have proper permissions over this tenant, detailed error: %s", tenantId, ex.getMessage()));
            return Mono.just(new ArrayList<>());
        });
    }

    private ResourceManager.Authenticated createAzureClient(AzureEnvironment env, String tenantId) {
        return configureAzure().authenticate(this.createTokenCredentialForTenant(tenantId), new AzureProfile(env));
    }

    private ResourceManager.Authenticated createAzureClient(AzureEnvironment env) {
        return configureAzure().authenticate(this.rootCredentialSupplier.get(), new AzureProfile(env));
    }

    /**
     * TODO: share the same code for creating ResourceManager.Configurable
     */
    private static ResourceManager.Configurable configureAzure() {
        // disable retry for getting tenant and subscriptions
        final String userAgent = Azure.az().config().getUserAgent();
        return ResourceManager.configure()
            .withHttpClient(AbstractAzServiceSubscription.getDefaultHttpClient())
            .withPolicy(AbstractAzServiceSubscription.getUserAgentPolicy(userAgent))
            .withRetryPolicy(new RetryPolicy(new FixedDelay(0, Duration.ofSeconds(0))));
    }

    private Mono<List<String>> loadTenantIdsIfAbsent(TokenCredentialManager tokenCredentialManager) {
        if (CollectionUtils.isNotEmpty(this.getTenantIds())) {
            return Mono.just(this.getTenantIds());
        }
        return tokenCredentialManager.listTenants();
    }

    private void requireAuthenticated() {
        if (!this.isApplicable()) {
            throw new AzureToolkitAuthenticationException("account is not available.");
        }
        if (this.credentialManager == null || this.getTenantIds() == null || !this.getSubscriptions().isEmpty()) {
            throw new AzureToolkitAuthenticationException("you are not signed-in.");
        }
    }

    public void setSelectedSubscriptions(List<String> selectedSubscriptionIds) {
        requireAuthenticated();
        if (CollectionUtils.isEmpty(selectedSubscriptionIds)) {
            throw new AzureToolkitRuntimeException("No subscriptions are selected. You must select at least one subscription.", IAccountActions.SELECT_SUBS);
        }
        if (CollectionUtils.isEmpty(getSubscriptions())) {
            throw new AzureToolkitRuntimeException("There are no subscriptions to select.", IAccountActions.TRY_AZURE);
        }
        if (this.getSubscriptions().stream().anyMatch(s -> Utils.containsIgnoreCase(selectedSubscriptionIds, s.getId()))) {
            markSubscriptionsSelected(selectedSubscriptionIds);
            AzureEventBus.emit("account.subscription_changed.account", this);
            final AzureTaskManager manager = AzureTaskManager.getInstance();
            if (Objects.nonNull(manager)) {
                manager.runOnPooledThread(Preloader::load);
            }
        } else {
            throw new AzureToolkitRuntimeException("the selected subscriptions are invalid", IAccountActions.SELECT_SUBS);
        }
    }

    private void markSubscriptionsSelected(@Nonnull List<String> subscriptionIds) {
        final List<Subscription> subscriptions = this.getSubscriptions();
        subscriptions.stream().filter(s -> Utils.containsIgnoreCase(subscriptionIds, s.getId())).forEach(s -> s.setSelected(true));
    }

    @Override
    public Subscription getSubscription(String subscriptionId) {
        return getSubscriptions().stream()
            .filter(s -> StringUtils.equalsIgnoreCase(subscriptionId, s.getId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(String.format("Cannot find subscription with id '%s'", subscriptionId)));
    }

    @Nonnull
    public List<Subscription> getSubscriptions() {
        requireAuthenticated();
        return Optional.ofNullable(this.subscriptions).orElse(Collections.emptyList());
    }

    private Subscription getSelectedSubscription(String subscriptionId) {
        return getSelectedSubscriptions().stream()
            .filter(s -> StringUtils.equalsIgnoreCase(subscriptionId, s.getId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(String.format("Cannot find a selected subscription with id '%s'", subscriptionId)));
    }

    @Override
    public List<Subscription> getSelectedSubscriptions() {
        return this.getSubscriptions().stream().filter(Subscription::isSelected).collect(Collectors.toList());
    }

    public List<String> getTenantIds() {
        return subscriptions.stream().map(Subscription::getTenantId).distinct().collect(Collectors.toList());
    }

    public String getPortalUrl() {
        return AzureEnvironmentUtils.getPortalUrl(this.getEnvironment());
    }

    @Override
    public String toString() {
        final List<String> details = new ArrayList<>();

        if (!this.isApplicable()) {
            return "<account not available>";
        }
        if (getAuthType() != null) {
            details.add(String.format("Auth type: %s", TextUtils.cyan(getAuthType().toString())));
        }
        if (this.isApplicable() && CollectionUtils.isNotEmpty(getSubscriptions())) {
            final List<Subscription> selectedSubscriptions = getSelectedSubscriptions();
            if (selectedSubscriptions != null && selectedSubscriptions.size() == 1) {
                details.add(String.format("Default subscription: %s(%s)", TextUtils.cyan(selectedSubscriptions.get(0).getName()),
                    TextUtils.cyan(selectedSubscriptions.get(0).getId())));
            }
        }

        if (StringUtils.isNotEmpty(this.getEmail())) {
            details.add(String.format("Username: %s", TextUtils.cyan(this.getEmail())));
        }

        return StringUtils.join(details.toArray(), "\n");
    }
}
