/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.springcloud.task;

import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.operation.OperationContext;
import com.microsoft.azure.toolkit.lib.common.task.AzureTask;
import com.microsoft.azure.toolkit.lib.springcloud.AzureSpringCloud;
import com.microsoft.azure.toolkit.lib.springcloud.SpringCloudAppDraft;
import com.microsoft.azure.toolkit.lib.springcloud.SpringCloudCluster;
import com.microsoft.azure.toolkit.lib.springcloud.SpringCloudDeployment;
import com.microsoft.azure.toolkit.lib.springcloud.SpringCloudDeploymentDraft;
import com.microsoft.azure.toolkit.lib.springcloud.config.SpringCloudAppConfig;
import com.microsoft.azure.toolkit.lib.springcloud.config.SpringCloudDeploymentConfig;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Getter
public class DeploySpringCloudAppTask extends AzureTask<SpringCloudDeployment> {
    public static final String DEFAULT_DEPLOYMENT_NAME = "default";

    private final SpringCloudAppConfig config;
    @Nonnull
    private final List<AzureTask<?>> subTasks;
    private SpringCloudDeployment deployment;

    public DeploySpringCloudAppTask(SpringCloudAppConfig appConfig) {
        this.config = appConfig;
        this.subTasks = this.initTasks();
    }

    @Nonnull
    private List<AzureTask<?>> initTasks() {
        // Init spring clients, and prompt users to confirm
        final SpringCloudDeploymentConfig deploymentConfig = config.getDeployment();
        final String clusterName = config.getClusterName();
        final String appName = config.getAppName();
        final String resourceGroup = config.getResourceGroup();
        final SpringCloudCluster cluster = Azure.az(AzureSpringCloud.class).clusters(config.getSubscriptionId()).get(clusterName, resourceGroup);
        Optional.ofNullable(cluster).orElseThrow(() -> new AzureToolkitRuntimeException(
            String.format("Azure Spring Apps(%s) is not found in subscription(%s).", clusterName, config.getSubscriptionId())));
        final SpringCloudAppDraft app = cluster.apps().updateOrCreate(appName, resourceGroup);
        final String deploymentName = StringUtils.firstNonBlank(
            deploymentConfig.getDeploymentName(),
            config.getActiveDeploymentName(),
            app.getActiveDeploymentName(),
            DEFAULT_DEPLOYMENT_NAME
        );
        final boolean toCreateApp = !app.exists();
        final boolean toCreateDeployment = toCreateApp && !app.deployments().exists(deploymentName, resourceGroup);
        config.setActiveDeploymentName(StringUtils.firstNonBlank(app.getActiveDeploymentName(), toCreateDeployment ? deploymentName : null));

        OperationContext.action().setTelemetryProperty("subscriptionId", config.getSubscriptionId());
        OperationContext.current().setTelemetryProperty("isCreateNewApp", String.valueOf(toCreateApp));
        OperationContext.current().setTelemetryProperty("isCreateDeployment", String.valueOf(toCreateDeployment));
        OperationContext.current().setTelemetryProperty("isDeploymentNameGiven", String.valueOf(StringUtils.isNotEmpty(deploymentConfig.getDeploymentName())));

        final AzureString CREATE_APP_TITLE = AzureString.format("Create new app({0}) in Azure Spring Apps({1})", appName, clusterName);
        final AzureString UPDATE_APP_TITLE = AzureString.format("Update app({0}) of Azure Spring Apps({1})", appName, clusterName);
        final AzureString CREATE_DEPLOYMENT_TITLE = AzureString.format("Create new deployment({0}) in app({1})", deploymentName, appName);
        final AzureString UPDATE_DEPLOYMENT_TITLE = AzureString.format("Update deployment({0}) of app({1})", deploymentName, appName);
        final AzureString MODIFY_DEPLOYMENT_TITLE = toCreateDeployment ? CREATE_DEPLOYMENT_TITLE : UPDATE_DEPLOYMENT_TITLE;

        final List<AzureTask<?>> tasks = new ArrayList<>();
        app.setConfig(config);
        if (toCreateApp) {
            tasks.add(new AzureTask<Void>(CREATE_APP_TITLE, app::createIfNotExist));
        }
        tasks.add(new AzureTask<Void>(MODIFY_DEPLOYMENT_TITLE, () -> {
            final SpringCloudDeploymentDraft draft = app.deployments().updateOrCreate(deploymentName, resourceGroup);
            draft.setConfig(config.getDeployment());
            this.deployment = draft.commit();
        }));
        tasks.add(new AzureTask<Void>(UPDATE_APP_TITLE, () -> {
            final SpringCloudAppDraft draft = (SpringCloudAppDraft) app.update();
            draft.setConfig(config);
            draft.updateIfExist();
        }));
        tasks.add(new AzureTask<Void>(app::reset));
        return tasks;
    }

    @Override
    @AzureOperation(name = "springcloud.create_update_app.app", params = {"this.config.getAppName()"}, type = AzureOperation.Type.SERVICE)
    public SpringCloudDeployment doExecute() throws Exception {
        for (final AzureTask<?> t : this.subTasks) {
            t.getBody().call();
        }
        return this.deployment;
    }
}
