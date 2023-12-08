/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.policy.FixedDelay;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.util.logging.ClientLogger;
import com.azure.identity.DeviceCodeCredential;
import com.azure.identity.InteractiveBrowserCredential;
import com.azure.identity.TokenCachePersistenceOptions;
import com.azure.identity.implementation.MsalToken;
import com.azure.identity.implementation.util.ScopeUtil;
import com.azure.resourcemanager.resources.ResourceManager;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.account.IAccount;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.cache.CacheEvict;
import com.microsoft.azure.toolkit.lib.common.cache.Preloader;
import com.microsoft.azure.toolkit.lib.common.event.AzureEventBus;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.model.AbstractAzServiceSubscription;
import com.microsoft.azure.toolkit.lib.common.model.Subscription;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.task.AzureTaskManager;
import com.microsoft.azure.toolkit.lib.common.utils.TextUtils;
import com.microsoft.azure.toolkit.lib.common.utils.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Getter
@RequiredArgsConstructor
public abstract class Account implements IAccount {
    protected static final TokenCachePersistenceOptions PERSISTENCE_OPTIONS = new TokenCachePersistenceOptions().setName("azure-toolkit.cache");
    private static final ClientLogger LOGGER = new ClientLogger(Account.class);
    private final Map<String, TokenCredential> tenantCredentialCache = new ConcurrentHashMap<>();
    @Nonnull
    private final AuthConfiguration config;
    protected String username;
    @Setter(AccessLevel.PACKAGE)
    protected boolean persistenceEnabled = true;
    @Getter(AccessLevel.PACKAGE)
    private TokenCredential defaultTokenCredential;
    @Getter(AccessLevel.NONE)
    private List<Subscription> subscriptions;

    @Nonnull
    protected abstract TokenCredential buildDefaultTokenCredential();

    public TokenCredential getTokenCredential(String subscriptionId) {
        final Subscription subscription = getSubscription(subscriptionId);
        return getTenantTokenCredential(subscription.getTenantId());
    }

    @Nonnull
    public TokenCredential getTenantTokenCredential(@Nonnull String tenantId) {
        if (StringUtils.isBlank(tenantId)) {
            throw new IllegalArgumentException("tenant id is required to retrieve credential.");
        } else {
            return this.tenantCredentialCache.computeIfAbsent(tenantId, tId -> new TenantTokenCredential(tId, this.defaultTokenCredential));
        }
    }

    void login() {
        this.defaultTokenCredential = this.buildDefaultTokenCredential();
        this.reloadSubscriptions();
        this.setupAfterLogin(this.defaultTokenCredential);
        this.config.setType(this.getType());
        this.config.setClient(this.getClientId());
        final List<String> tenantIds = this.getTenantIds();
        if (StringUtils.isEmpty(this.config.getTenant())) {
            this.config.setTenant(CollectionUtils.isEmpty(tenantIds) ? null : tenantIds.get(0));
        }
        this.config.setEnvironment(AzureEnvironmentUtils.azureEnvironmentToString(this.getEnvironment()));
        this.config.setUsername(this.getUsername());
    }

    public abstract boolean checkAvailable();

    @Nonnull
    protected Optional<AccessToken> getManagementToken() {
        final String[] scopes = ScopeUtil.resourceToScopes(this.getEnvironment().getManagementEndpoint());
        final TokenRequestContext request = new TokenRequestContext().addScopes(scopes);
        try {
            return this.buildDefaultTokenCredential().getToken(request)
                .onErrorResume(Exception.class, t -> Mono.empty())
                .blockOptional();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    protected void setupAfterLogin(TokenCredential defaultTokenCredential) {
        final String[] scopes = ScopeUtil.resourceToScopes(this.getEnvironment().getManagementEndpoint());
        final TokenRequestContext request = new TokenRequestContext().addScopes(scopes);
        final AccessToken token = defaultTokenCredential.getToken(request).blockOptional()
            .orElseThrow(() -> new AzureToolkitAuthenticationException("Failed to retrieve token."));
        if (token instanceof MsalToken) {
            this.username = Optional.of((MsalToken) token)
                .map(MsalToken::getAccount).map(com.microsoft.aad.msal4j.IAccount::username)
                .orElse(this.getClientId());
        }
        Optional.ofNullable(this.getConfig().getDoAfterLogin()).ifPresent(Runnable::run);
    }

    @CacheEvict(CacheEvict.ALL)
        // evict all caches on signing out
    void logout() {
        this.subscriptions = null;
        this.defaultTokenCredential = null;
    }

    @AzureOperation(name = "azure/account.reload_subscriptions")
    public List<Subscription> reloadSubscriptions() {
        final List<String> selected = Optional.ofNullable(this.subscriptions).orElse(Collections.emptyList())
            .stream().filter(Subscription::isSelected)
            .map(Subscription::getId)
            .collect(Collectors.toList());
        this.subscriptions = Optional.ofNullable(this.loadSubscriptions()).orElse(Collections.emptyList()).stream()
            .sorted(Comparator.comparing(s -> s.getName().toLowerCase()))
            .collect(Collectors.toList());
        this.subscriptions.stream()
            .filter(s -> selected.contains(s.getId().toLowerCase()))
            .forEach(s -> s.setSelected(true));
        return this.getSubscriptions();
    }

    @AzureOperation(name = "azure/account.load_subscriptions")
    protected List<Subscription> loadSubscriptions() {
        final TokenCredential credential = this.defaultTokenCredential;
        final ResourceManager.Authenticated client = configureAzure().authenticate(credential, new AzureProfile(this.getEnvironment()));
        return client.tenants().listAsync()
            .flatMap(t -> this.loadSubscriptions(t.tenantId()))
            .filter(Utils.distinctByKey(Subscription::getId))
            .collectList().block();
    }

    @Nonnull
    @AzureOperation(name = "azure/account.load_subscriptions.tenant", params = "tenantId")
    private Flux<Subscription> loadSubscriptions(String tenantId) {
        final TokenCredential credential = this.getTenantTokenCredential(tenantId);
        final AzureProfile profile = new AzureProfile(tenantId, null, this.getEnvironment());
        final ResourceManager.Authenticated client = configureAzure().authenticate(credential, profile);
        return client.subscriptions().listAsync().onErrorResume(ex -> {
                AzureMessager.getMessager().warning(AzureString.format(
                    "Failed to get subscriptions for tenant %s, please confirm you have sufficient permissions." +
                        " Use %s to explicitly login to a tenant if it requires Multi-Factor Authentication (MFA)." +
                        " Message: %s", tenantId, "-Dauth.tenant=TENANT_ID", ex.getMessage()));
                return Flux.fromIterable(new ArrayList<>());
        }).map(Subscription::new);
    }

    @Nonnull
    public List<Subscription> getSubscriptions() {
        if (!this.isLoggedIn()) {
            final String cause = "You are not signed-in or there are no subscriptions in your current Account.";
            throw new AzureToolkitRuntimeException(cause, IAccountActions.AUTHENTICATE, IAccountActions.TRY_AZURE);
        }
        return new ArrayList<>(Optional.ofNullable(this.subscriptions).orElse(Collections.emptyList()));
    }

    public void setSelectedSubscriptions(List<String> selectedSubscriptionIds) {
        if (CollectionUtils.isEmpty(selectedSubscriptionIds)) {
            throw new AzureToolkitRuntimeException("No subscriptions are selected. You must select at least one subscription.", IAccountActions.SELECT_SUBS);
        }
        final Set<String> selected = selectedSubscriptionIds.stream().map(String::toLowerCase).collect(Collectors.toSet());
        this.getSubscriptions().forEach(s -> s.setSelected(false));
        this.getSubscriptions().stream()
            .filter(s -> selected.contains(s.getId().toLowerCase()))
            .forEach(s -> s.setSelected(true));
        this.config.setSelectedSubscriptions(selectedSubscriptionIds);
        AzureEventBus.emit("account.subscription_changed.account", this);
        final AzureTaskManager manager = AzureTaskManager.getInstance();
        final Boolean enablePreloading = Azure.az().config().getEnablePreloading();
        if (Objects.nonNull(manager) && BooleanUtils.isTrue(enablePreloading)) {
            manager.runOnPooledThread(Preloader::load);
        }
    }

    @Override
    public Subscription getSubscription(String subscriptionId) {
        return this.getSubscriptions().stream()
            .filter(s -> StringUtils.equalsIgnoreCase(subscriptionId, s.getId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(String.format("Cannot find subscription with id '%s'", subscriptionId)));
    }

    public Subscription getSelectedSubscription(String subscriptionId) {
        return getSelectedSubscriptions().stream()
            .filter(s -> StringUtils.equalsIgnoreCase(subscriptionId, s.getId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(String.format("Cannot find a selected subscription with id '%s'", subscriptionId)));
    }

    @Override
    public List<Subscription> getSelectedSubscriptions() {
        return this.getSubscriptions().stream().filter(Subscription::isSelected).collect(Collectors.toList());
    }

    @Nonnull
    public List<String> getTenantIds() {
        return getSubscriptions().stream().map(Subscription::getTenantId).distinct().collect(Collectors.toList());
    }

    public String getPortalUrl() {
        return AzureEnvironmentUtils.getPortalUrl(this.getEnvironment());
    }

    public AzureEnvironment getEnvironment() {
        return Azure.az(AzureCloud.class).getOrDefault();
    }

    public boolean isLoggedInCompletely() {
        return isLoggedIn() && CollectionUtils.isNotEmpty(this.getSelectedSubscriptions());
    }

    public boolean isLoggedIn() {
        return Objects.nonNull(this.defaultTokenCredential) && CollectionUtils.isNotEmpty(this.subscriptions);
    }

    public boolean isSubscriptionsSelected() {
        return isLoggedInCompletely();
    }

    @Nullable
    protected TokenCachePersistenceOptions getPersistenceOptions() {
        return isPersistenceEnabled() ? PERSISTENCE_OPTIONS : null;
    }

    private static ResourceManager.Configurable configureAzure() {
        // disable retry for getting tenant and subscriptions
        return ResourceManager.configure()
            .withHttpClient(AbstractAzServiceSubscription.getDefaultHttpClient())
            .withPolicy(AbstractAzServiceSubscription.getUserAgentPolicy())
            .withRetryPolicy(new RetryPolicy(new FixedDelay(0, Duration.ofSeconds(0))));
    }

    @Override
    public String toString() {
        final List<String> details = new ArrayList<>();
        final String username = this.getUsername();
        if (getType() != null) {
            details.add(String.format("Auth type: %s", TextUtils.cyan(getType().toString())));
        }
        if (StringUtils.isNotEmpty(username)) {
            details.add(String.format("Username: %s", TextUtils.cyan(username.trim())));
        }

        return StringUtils.join(details.toArray(), "\n");
    }

    @RequiredArgsConstructor
    private static class TenantTokenCredential implements TokenCredential {
        // cache for different resources on the same tenant
        // private final Map<String, SimpleTokenCache> resourceTokenCache = new ConcurrentHashMap<>();
        private final String tenantId;
        private final TokenCredential defaultCredential;

        @Override
        public Mono<AccessToken> getToken(TokenRequestContext request) {
            request.setTenantId(StringUtils.firstNonBlank(request.getTenantId(), this.tenantId));
            // final String resource = ScopeUtil.scopesToResource(request.getScopes());
            // final Function<String, SimpleTokenCache> func = (ignore) -> new SimpleTokenCache(() -> defaultCredential.getToken(request));
            // return resourceTokenCache.computeIfAbsent(resource, func).getToken();
            // final Mono<AccessToken> token = defaultCredential.getToken(request);
            // final String resource = ScopeUtil.scopesToResource(request.getScopes());
            return defaultCredential.getToken(request).doOnTerminate(() -> {
                if (defaultCredential instanceof InteractiveBrowserCredential || defaultCredential instanceof DeviceCodeCredential) {
                    disableAutomaticAuthentication(); // disable after first success.
                }
            });
        }

        @SneakyThrows
        private void disableAutomaticAuthentication() {
            final Field automaticField = FieldUtils.getField(this.defaultCredential.getClass(), "automaticAuthentication", true);
            if (Objects.nonNull(automaticField) && ((boolean) FieldUtils.readField(automaticField, this.defaultCredential))) {
                FieldUtils.writeField(automaticField, this.defaultCredential, false);
            }
        }
    }

    public abstract AuthType getType();

    public String getClientId() {
        return Optional.ofNullable(this.config.getClient()).orElse("04b07795-8ddb-461a-bbee-02f9e1bf7b46");
    }
}
