/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.function;

import com.azure.resourcemanager.appservice.AppServiceManager;
import com.azure.resourcemanager.appservice.models.FunctionAppBasic;
import com.azure.resourcemanager.appservice.models.FunctionApps;
import com.azure.resourcemanager.appservice.models.WebSiteBase;
import com.microsoft.azure.toolkit.lib.appservice.AppServiceServiceSubscription;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.model.AbstractAzResourceModule;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import lombok.CustomLog;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

@CustomLog
public class FunctionAppModule extends AbstractAzResourceModule<FunctionApp, AppServiceServiceSubscription, WebSiteBase> {

    public static final String NAME = "sites";

    public FunctionAppModule(@Nonnull AppServiceServiceSubscription parent) {
        super(NAME, parent);
    }

    @Override
    public FunctionApps getClient() {
        return Optional.ofNullable(this.parent.getRemote()).map(AppServiceManager::functionApps).orElse(null);
    }

    @Nonnull
    @Override
    protected FunctionAppDraft newDraftForCreate(@Nonnull String name, String resourceGroupName) {
        return new FunctionAppDraft(name, resourceGroupName, this);
    }

    @Nonnull
    @Override
    protected FunctionAppDraft newDraftForUpdate(@Nonnull FunctionApp origin) {
        return new FunctionAppDraft(origin);
    }

    @Nonnull
    protected FunctionApp newResource(@Nonnull WebSiteBase remote) {
        return new FunctionApp((FunctionAppBasic) remote, this);
    }

    @Nonnull
    protected FunctionApp newResource(@Nonnull String name, @Nullable String resourceGroupName) {
        return new FunctionApp(name, Objects.requireNonNull(resourceGroupName), this);
    }

    @Nonnull
    @Override
    public String getResourceTypeName() {
        return "Function app";
    }

    @Nonnull
    @Override
    public FunctionApp update(@Nonnull AzResource.Draft<FunctionApp, ?> d) {
        final AzResource.Draft<FunctionApp, WebSiteBase> draft = this.cast(d);
        log.debug(String.format("[%s]:update(draft:%s)", this.getName(), draft));
        final FunctionApp resource = this.get(draft.getName(), draft.getResourceGroupName());
        if (Objects.nonNull(resource) && Objects.nonNull(resource.getRemote())) {
            log.debug(String.format("[%s]:update->doModify(draft.updateResourceInAzure(%s))", this.getName(), resource.getRemote()));
            resource.doModify(() -> draft.updateResourceInAzure(Objects.requireNonNull(resource.getFullRemote())), AzResource.Status.UPDATING);
            return resource;
        }
        throw new AzureToolkitRuntimeException(String.format("resource \"%s\" doesn't exist", draft.getName()));
    }
}
