/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.applicationinsights;

import com.azure.resourcemanager.applicationinsights.ApplicationInsightsManager;
import com.azure.resourcemanager.applicationinsights.models.ApplicationInsightsComponent;
import com.azure.resourcemanager.applicationinsights.models.ApplicationType;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.common.action.Action;
import com.microsoft.azure.toolkit.lib.common.action.AzureActionManager;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.messager.IAzureMessager;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import com.microsoft.azure.toolkit.lib.common.model.Region;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.monitor.AzureLogAnalyticsWorkspace;
import com.microsoft.azure.toolkit.lib.monitor.LogAnalyticsWorkspaceConfig;
import com.microsoft.azure.toolkit.lib.monitor.LogAnalyticsWorkspaceDraft;
import com.microsoft.azure.toolkit.lib.resource.AzureResources;
import com.microsoft.azure.toolkit.lib.resource.ResourceGroup;
import com.microsoft.azure.toolkit.lib.resource.ResourceGroupDraft;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public class ApplicationInsightDraft extends ApplicationInsight implements AzResource.Draft<ApplicationInsight, ApplicationInsightsComponent> {

    private static final String REGION_IS_REQUIRED = "'region' is required to create Application Insights.";
    private static final String START_CREATING_APPLICATION_INSIGHT = "Start creating Application Insights ({0})...";
    private static final String APPLICATION_INSIGHTS_CREATED = "Application Insights ({0}) is successfully created.";

    @Setter
    @Nullable
    private Region region;
    @Setter
    @Getter
    @Nullable
    private LogAnalyticsWorkspaceConfig workspaceConfig;
    @Getter
    @Nullable
    private final ApplicationInsight origin;

    protected ApplicationInsightDraft(@Nonnull String name, @Nonnull String resourceGroupName, @Nonnull ApplicationInsightsModule module) {
        super(name, resourceGroupName, module);
        this.origin = null;
    }

    protected ApplicationInsightDraft(@Nonnull ApplicationInsight origin) {
        super(origin);
        this.origin = origin;
    }

    @Override
    public void reset() {
        this.region = null;
    }

    @Nonnull
    @Override
    @AzureOperation(name = "azure/ai.create_ai.ai", params = {"this.getName()"})
    public ApplicationInsightsComponent createResourceInAzure() {
        if (Objects.isNull(region)) {
            throw new AzureToolkitRuntimeException(REGION_IS_REQUIRED);
        }
        final String workspaceResourceId = extractWorkspaceId();
        final ApplicationInsightsManager applicationInsightsManager = Objects.requireNonNull(this.getParent().getRemote());
        final IAzureMessager messager = AzureMessager.getMessager();
        messager.info(AzureString.format(START_CREATING_APPLICATION_INSIGHT, getName()));
        final ApplicationInsightsComponent result = applicationInsightsManager.components().define(getName())
            .withRegion(region.getName())
            .withExistingResourceGroup(getResourceGroupName())
            .withKind("web")
            .withWorkspaceResourceId(workspaceResourceId)
            .withApplicationType(ApplicationType.WEB).create();
        final Action<AzResource> connect = Optional.ofNullable(AzureActionManager.getInstance().getAction(AzResource.CONNECT_RESOURCE))
            .map(action -> action.bind(this)).orElse(null);
        final Action<ApplicationInsight> liveMetrics = Optional.ofNullable(AzureActionManager.getInstance().getAction(OPEN_LIVE_METRICS))
            .map(action -> action.bind(this)).orElse(null);
        final Action<ApplicationInsight> copyConnectionString = Optional.ofNullable(AzureActionManager.getInstance().getAction(COPY_CONNECTION_STRING))
            .map(action -> action.bind(this)).orElse(null);
        final Object[] actions = Stream.of(connect, liveMetrics, copyConnectionString).filter(Objects::nonNull).toArray();
        messager.success(AzureString.format(APPLICATION_INSIGHTS_CREATED, getName(), getPortalUrl()), actions);
        return result;
    }

    @Nonnull
    @Override
    @AzureOperation(name = "azure/ai.update_ai.ai", params = {"this.getName()"})
    public ApplicationInsightsComponent updateResourceInAzure(@Nonnull ApplicationInsightsComponent origin) {
        throw new AzureToolkitRuntimeException("not supported");
    }

    @Nullable
    @Override
    public Region getRegion() {
        return Optional.ofNullable(this.region).orElseGet(super::getRegion);
    }

    @Override
    public boolean isModified() {
        return this.region != null && !Objects.equals(this.region, this.origin.getRegion());
    }

    @Nullable
    private String extractWorkspaceId() {
        if (Objects.isNull(workspaceConfig)) {
            return null;
        }
        String workspaceResourceId;
        if (this.workspaceConfig.isNewCreate()) {
            final String resourceGroupName = String.format("DefaultResourceGroup-%s", region.getAbbreviation());
            final ResourceGroup resourceGroup =
                    Azure.az(AzureResources.class).groups(getSubscriptionId()).getOrDraft(resourceGroupName, resourceGroupName);
            if (resourceGroup.isDraftForCreating()) {
                ((ResourceGroupDraft) resourceGroup).setRegion(region);
                ((ResourceGroupDraft) resourceGroup).createIfNotExist();
            }
            final LogAnalyticsWorkspaceDraft draft = Azure.az(AzureLogAnalyticsWorkspace.class)
                    .logAnalyticsWorkspaces(getSubscriptionId())
                    .create(workspaceConfig.getName(), resourceGroupName);
            draft.setRegion(this.region);
            workspaceResourceId = draft.createIfNotExist().getId();
        } else {
            workspaceResourceId = workspaceConfig.getResourceId();
        }
        return workspaceResourceId;
    }
}
