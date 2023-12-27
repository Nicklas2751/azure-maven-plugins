/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.mysql;

import com.azure.resourcemanager.mysqlflexibleserver.MySqlManager;
import com.azure.resourcemanager.mysqlflexibleserver.models.CapabilityProperties;
import com.azure.resourcemanager.mysqlflexibleserver.models.EnableStatusEnum;
import com.azure.resourcemanager.mysqlflexibleserver.models.Server;
import com.azure.resourcemanager.mysqlflexibleserver.models.ServerEditionCapability;
import com.azure.resourcemanager.mysqlflexibleserver.models.ServerVersion;
import com.azure.resourcemanager.mysqlflexibleserver.models.Sku;
import com.azure.resourcemanager.mysqlflexibleserver.models.SkuCapability;
import com.azure.resourcemanager.mysqlflexibleserver.models.SkuTier;
import com.azure.resourcemanager.mysqlflexibleserver.models.Storage;
import com.microsoft.azure.toolkit.lib.common.action.Action;
import com.microsoft.azure.toolkit.lib.common.action.AzureActionManager;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.messager.IAzureMessager;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import com.microsoft.azure.toolkit.lib.common.model.Region;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.task.AzureTaskManager;
import com.microsoft.azure.toolkit.lib.database.DatabaseServerConfig;
import lombok.Data;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class MySqlServerDraft extends MySqlServer implements AzResource.Draft<MySqlServer, Server> {
    @Getter
    @Nullable
    private final MySqlServer origin;
    @Nullable
    private Config config;

    MySqlServerDraft(@Nonnull String name, @Nonnull String resourceGroupName, @Nonnull MySqlServerModule module) {
        super(name, resourceGroupName, module);
        this.origin = null;
    }

    MySqlServerDraft(@Nonnull MySqlServer origin) {
        super(origin);
        this.origin = origin;
    }

    @Override
    public void reset() {
        this.config = null;
    }

    public void setConfig(@Nonnull DatabaseServerConfig config) {
        this.setAdminName(config.getAdminName());
        this.setAdminPassword(config.getAdminPassword());
        this.setRegion(config.getRegion());
        this.setVersion(config.getVersion());
        this.setFullyQualifiedDomainName(config.getFullyQualifiedDomainName());
        this.setAzureServiceAccessAllowed(config.isAzureServiceAccessAllowed());
        this.setLocalMachineAccessAllowed(config.isLocalMachineAccessAllowed());
    }

    @Nullable
    private ServerVersion validateServerVersion(String version) {
        if (StringUtils.isNotBlank(version)) {
            final ServerVersion res = ServerVersion.fromString(version);
            if (res == null) {
                throw new AzureToolkitRuntimeException(String.format("Invalid postgre version '%s'.", version));
            }
            return res;
        }
        return null;
    }

    @Nonnull
    @Override
    @AzureOperation(name = "azure/mysql.create_server.server", params = {"this.getName()"})
    public Server createResourceInAzure() {
        assert this.config != null;
        final MySqlManager manager = Objects.requireNonNull(this.getParent().getRemote());
        final String region = Objects.requireNonNull(this.getRegion(), "'region' is required to create MySQL flexible server.").getName();

        // refer to https://learn.microsoft.com/en-us/rest/api/mysql/flexibleserver/location-based-capabilities/list?view=rest-mysql-flexibleserver-2023-06-01-preview&tabs=HTTP
        final List<CapabilityProperties> zones = Objects.requireNonNull(manager).locationBasedCapabilities().list(region).stream()
            .filter(c -> !c.zone().equalsIgnoreCase("none"))
            .collect(Collectors.toList());
        if (zones.isEmpty()) {
            throw new AzureToolkitRuntimeException("No available zones for current subscription.");
        }
        final CapabilityProperties zone = zones.get(0);
        final List<ServerEditionCapability> editions = zone.supportedFlexibleServerEditions();
        if (editions.isEmpty()) {
            throw new AzureToolkitRuntimeException("No available MySQL server editions.");
        }
        final ServerEditionCapability edition = editions.get(0);
        final SkuTier tier = SkuTier.fromString(edition.name());
        final List<SkuCapability> skus = edition.supportedServerVersions().stream()
            .filter(v -> StringUtils.equalsIgnoreCase(v.name(), this.getVersion()))
            .flatMap(v -> v.supportedSkus().stream())
            .collect(Collectors.toList());
        if (skus.isEmpty()) {
            throw new AzureToolkitRuntimeException(String.format("Version '%s' is not supported in region '%s'.", this.getVersion(), region));
        }
        final SkuCapability skuCapability = skus.get(0);
        final Sku sku = new Sku().withName(skuCapability.name()).withTier(tier);
        // create server
        final Server.DefinitionStages.WithCreate create = manager.servers().define(this.getName())
            .withRegion(region)
            .withExistingResourceGroup(this.getResourceGroupName())
            .withStorage(new Storage().withIops(skuCapability.supportedIops().intValue()).withStorageSizeGB(20).withAutoGrow(EnableStatusEnum.ENABLED))
            .withAvailabilityZone(zone.zone())
            .withAdministratorLogin(this.getAdminName())
            .withAdministratorLoginPassword(this.getAdminPassword())
            .withVersion(validateServerVersion(this.getVersion()))
            .withSku(sku);
        final IAzureMessager messager = AzureMessager.getMessager();
        messager.info(AzureString.format("Start creating MySQL server ({0})...", this.getName()));
        final Server remote = create.create();
        final Action<AzResource> connect = AzureActionManager.getInstance().getAction(AzResource.CONNECT_RESOURCE).bind(this);
        messager.success(AzureString.format("MySQL server({0}) is successfully created.", this.getName()), connect);
        if (this.isAzureServiceAccessAllowed() != super.isAzureServiceAccessAllowed() ||
            this.isLocalMachineAccessAllowed() != super.isLocalMachineAccessAllowed()) {
            AzureTaskManager.getInstance().runInBackground(AzureString.format("Update firewall rules of MySQL server({0}).", this.getName()), () -> {
                messager.info(AzureString.format("Start updating firewall rules of MySQL server ({0})...", this.getName()));
                this.firewallRules().toggleAzureServiceAccess(this.isAzureServiceAccessAllowed());
                this.firewallRules().toggleLocalMachineAccess(this.isLocalMachineAccessAllowed());
                messager.success(AzureString.format("Firewall rules of MySQL server({0}) is successfully updated.", this.getName()));
            });
        }
        return remote;
    }

    @Nonnull
    @Override
    @AzureOperation(name = "azure/mysql.update_server.server", params = {"this.getName()"})
    public Server updateResourceInAzure(@Nonnull Server origin) {
        // TODO: update other properties
        if (this.isAzureServiceAccessAllowed() != super.isAzureServiceAccessAllowed() ||
            this.isLocalMachineAccessAllowed() != super.isLocalMachineAccessAllowed()) {
            final IAzureMessager messager = AzureMessager.getMessager();
            messager.info(AzureString.format("Start updating firewall rules of MySQL server ({0})...", this.getName()));
            this.firewallRules().toggleAzureServiceAccess(this.isAzureServiceAccessAllowed());
            this.firewallRules().toggleLocalMachineAccess(this.isLocalMachineAccessAllowed());
            messager.success(AzureString.format("Firewall rules of MySQL server({0}) is successfully updated.", this.getName()));
        }
        return origin;
    }

    @Nonnull
    private synchronized Config ensureConfig() {
        this.config = Optional.ofNullable(this.config).orElseGet(Config::new);
        return this.config;
    }

    @Nullable
    @Override
    public String getAdminName() {
        return Optional.ofNullable(this.config).map(Config::getAdminName).orElseGet(super::getAdminName);
    }

    @Nullable
    public String getAdminPassword() {
        return Optional.ofNullable(this.config).map(Config::getAdminPassword).orElse(null);
    }

    @Nullable
    public Region getRegion() {
        return Optional.ofNullable(config).map(Config::getRegion).orElseGet(super::getRegion);
    }

    @Nullable
    @Override
    public String getVersion() {
        return Optional.ofNullable(this.config).map(Config::getVersion).orElseGet(super::getVersion);
    }

    @Nullable
    @Override
    public String getFullyQualifiedDomainName() {
        return Optional.ofNullable(this.config).map(Config::getFullyQualifiedDomainName).orElseGet(super::getFullyQualifiedDomainName);
    }

    @Override
    public boolean isLocalMachineAccessAllowed() {
        return Optional.ofNullable(this.config).map(Config::isLocalMachineAccessAllowed).orElseGet(super::isLocalMachineAccessAllowed);
    }

    @Override
    public boolean isAzureServiceAccessAllowed() {
        return Optional.ofNullable(this.config).map(Config::isAzureServiceAccessAllowed).orElseGet(super::isAzureServiceAccessAllowed);
    }

    public void setAdminName(String name) {
        this.ensureConfig().setAdminName(name);
    }

    public void setAdminPassword(String password) {
        this.ensureConfig().setAdminPassword(password);
    }

    public void setRegion(Region region) {
        this.ensureConfig().setRegion(region);
    }

    public void setVersion(String version) {
        this.ensureConfig().setVersion(version);
    }

    public void setFullyQualifiedDomainName(String name) {
        this.ensureConfig().setFullyQualifiedDomainName(name);
    }

    public void setLocalMachineAccessAllowed(boolean allowed) {
        this.ensureConfig().setLocalMachineAccessAllowed(allowed);
    }

    public void setAzureServiceAccessAllowed(boolean allowed) {
        this.ensureConfig().setAzureServiceAccessAllowed(allowed);
    }

    @Override
    public boolean isModified() {
        final boolean notModified = Objects.isNull(this.config) ||
            Objects.equals(this.config.isLocalMachineAccessAllowed(), super.isLocalMachineAccessAllowed()) ||
            Objects.equals(this.config.isAzureServiceAccessAllowed(), super.isAzureServiceAccessAllowed()) ||
            Objects.isNull(this.config.getAdminPassword()) ||
            Objects.isNull(this.config.getAdminName()) || Objects.equals(this.config.getAdminName(), super.getAdminName()) ||
            Objects.isNull(this.config.getRegion()) || Objects.equals(this.config.getRegion(), super.getRegion()) ||
            Objects.isNull(this.config.getVersion()) || Objects.equals(this.config.getVersion(), super.getVersion()) ||
            Objects.isNull(this.config.getFullyQualifiedDomainName()) ||
            Objects.equals(this.config.getFullyQualifiedDomainName(), super.getFullyQualifiedDomainName());
        return !notModified;
    }

    @Data
    private static class Config {
        private String adminName;
        private String adminPassword;
        private Region region;
        private String version;
        private String fullyQualifiedDomainName;
        private boolean azureServiceAccessAllowed;
        private boolean localMachineAccessAllowed;
    }
}