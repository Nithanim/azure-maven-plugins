/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.plan;

import com.azure.resourcemanager.appservice.AppServiceManager;
import com.azure.resourcemanager.appservice.models.AppServicePlan.DefinitionStages;
import com.azure.resourcemanager.appservice.models.AppServicePlan.Update;
import com.microsoft.azure.toolkit.lib.appservice.config.AppServicePlanConfig;
import com.microsoft.azure.toolkit.lib.appservice.model.OperatingSystem;
import com.microsoft.azure.toolkit.lib.appservice.model.PricingTier;
import com.microsoft.azure.toolkit.lib.appservice.utils.AppServiceUtils;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.messager.IAzureMessager;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import com.microsoft.azure.toolkit.lib.common.model.Region;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.telemetry.AzureTelemetry;
import com.microsoft.azure.toolkit.lib.common.validator.SchemaValidator;
import com.microsoft.azure.toolkit.lib.resource.task.CreateResourceGroupTask;
import lombok.Data;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

public class AppServicePlanDraft extends AppServicePlan implements
        AzResource.Draft<AppServicePlan, com.azure.resourcemanager.appservice.models.AppServicePlan> {
    private static final String CREATE_NEW_APP_SERVICE_PLAN = "createNewAppServicePlan";

    @Getter
    @Nullable
    private final AppServicePlan origin;
    @Nullable
    private Config config;

    AppServicePlanDraft(@Nonnull String name, @Nonnull String resourceGroupName, @Nonnull AppServicePlanModule module) {
        super(name, resourceGroupName, module);
        this.origin = null;
    }

    AppServicePlanDraft(@Nonnull AppServicePlan origin) {
        super(origin);
        this.origin = origin;
    }

    @Override
    public void reset() {
        this.config = null;
    }

    @Nonnull
    private synchronized Config ensureConfig() {
        this.config = Optional.ofNullable(this.config).orElseGet(Config::new);
        return this.config;
    }

    public void setPlanConfig(@Nonnull AppServicePlanConfig config) {
        this.setPricingTier(config.pricingTier());
        this.setOperatingSystem(config.os());
        this.setRegion(config.region());
    }

    @Nonnull
    public AppServicePlanConfig getPlanConfig() {
        final AppServicePlanConfig servicePlanConfig = new AppServicePlanConfig();
        servicePlanConfig.subscriptionId(this.getSubscriptionId());
        servicePlanConfig.servicePlanName(this.getName());
        servicePlanConfig.servicePlanResourceGroup(this.getResourceGroupName());
        servicePlanConfig.pricingTier(this.getPricingTier());
        servicePlanConfig.os(this.getOperatingSystem());
        servicePlanConfig.region(this.getRegion());
        return servicePlanConfig;
    }

    @Nonnull
    @Override
    @AzureOperation(
        name = "resource.create_resource.resource|type",
        params = {"this.getName()", "this.getResourceTypeName()"},
        type = AzureOperation.Type.SERVICE
    )
    public com.azure.resourcemanager.appservice.models.AppServicePlan createResourceInAzure() {
        SchemaValidator.getInstance().validateAndThrow("appservice/CreateAppServicePlan", this.getPlanConfig());
        AzureTelemetry.getContext().getActionParent().setProperty(CREATE_NEW_APP_SERVICE_PLAN, String.valueOf(true));

        final String name = this.getName();
        final OperatingSystem newOs = Objects.requireNonNull(this.getOperatingSystem(), "'operating system' is required to create App Service plan.");
        final PricingTier newTier = Objects.requireNonNull(this.getPricingTier(), "'pricing tier' is required to create App Service plan.");
        final Region newRegion = Objects.requireNonNull(this.getRegion(), "'region' is required to create App Service plan.");

        new CreateResourceGroupTask(this.getSubscriptionId(), this.getResourceGroupName(), newRegion).doExecute();

        final AppServiceManager manager = Objects.requireNonNull(this.getParent().getRemote());
        final DefinitionStages.WithCreate withCreate = manager.appServicePlans().define(name)
            .withRegion(newRegion.getName())
            .withExistingResourceGroup(this.getResourceGroupName())
            .withPricingTier(AppServiceUtils.toPricingTier(newTier))
            .withOperatingSystem(convertOS(newOs));

        final IAzureMessager messager = AzureMessager.getMessager();
        messager.info(AzureString.format("Start creating App Service plan ({0})...", name));
        com.azure.resourcemanager.appservice.models.AppServicePlan plan = Objects.requireNonNull(this.doModify(() -> withCreate.create(), Status.CREATING));
        messager.success(AzureString.format("App Service plan ({0}) is successfully created", name));
        return plan;
    }

    private com.azure.resourcemanager.appservice.models.OperatingSystem convertOS(OperatingSystem operatingSystem) {
        return operatingSystem == OperatingSystem.WINDOWS ?
            com.azure.resourcemanager.appservice.models.OperatingSystem.WINDOWS :
            com.azure.resourcemanager.appservice.models.OperatingSystem.LINUX; // Using Linux for Docker app service
    }

    @Nonnull
    @Override
    @AzureOperation(
        name = "resource.update_resource.resource|type",
        params = {"this.getName()", "this.getResourceTypeName()"},
        type = AzureOperation.Type.SERVICE
    )
    public com.azure.resourcemanager.appservice.models.AppServicePlan updateResourceInAzure(
            @Nonnull com.azure.resourcemanager.appservice.models.AppServicePlan remote) {
        assert origin != null : "updating target is not specified.";
        final PricingTier newTier = this.getPricingTier();
        final PricingTier oldTier = origin.getPricingTier();

        final boolean modified = Objects.nonNull(newTier) && !Objects.equals(newTier, oldTier);
        Update update = remote.update();
        if (!Objects.equals(this.getRegion(), origin.getRegion())) {
            AzureMessager.getMessager().warning(
                    AzureString.format("Skip region update for existing service plan ({0}) since it is not allowed.", remote.name()));
        }
        com.azure.resourcemanager.appservice.models.AppServicePlan result = remote;
        if (modified) {
            update = update.withPricingTier(AppServiceUtils.toPricingTier(newTier));
            final IAzureMessager messager = AzureMessager.getMessager();
            messager.info(AzureString.format("Start updating App Service plan ({0})...", remote.name()));
            result = update.apply();
            messager.success(AzureString.format("App Service plan ({0}) is successfully updated", remote.name()));
        }
        return result;
    }

    @Nullable
    @Override
    public Region getRegion() {
        return Optional.ofNullable(config).map(Config::getRegion).orElseGet(super::getRegion);
    }

    public void setRegion(Region region) {
        this.ensureConfig().setRegion(region);
    }

    @Nullable
    @Override
    public PricingTier getPricingTier() {
        return Optional.ofNullable(config).map(Config::getPricingTier).orElseGet(super::getPricingTier);
    }

    public void setPricingTier(PricingTier tier) {
        this.ensureConfig().setPricingTier(tier);
    }

    @Nullable
    @Override
    public OperatingSystem getOperatingSystem() {
        return Optional.ofNullable(config).map(Config::getOperatingSystem).orElseGet(super::getOperatingSystem);
    }

    public void setOperatingSystem(OperatingSystem os) {
        this.ensureConfig().setOperatingSystem(os);
    }

    @Override
    public boolean isModified() {
        final boolean notModified = Objects.isNull(this.config) ||
            Objects.isNull(this.config.getRegion()) || Objects.equals(this.config.getRegion(), super.getRegion()) ||
            Objects.isNull(this.config.getPricingTier()) || Objects.equals(this.config.getPricingTier(), super.getPricingTier()) ||
            Objects.isNull(this.config.getOperatingSystem()) || Objects.equals(this.config.getOperatingSystem(), super.getOperatingSystem());
        return !notModified;
    }

    /**
     * {@code null} means not modified for properties
     */
    @Data
    private static class Config {
        private Region region;
        private PricingTier pricingTier;
        private OperatingSystem operatingSystem;
    }
}
