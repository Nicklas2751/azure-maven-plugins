/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.common.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.microsoft.azure.toolkit.lib.common.utils.aspect.ExpressionUtils;
import com.microsoft.azure.toolkit.lib.common.utils.aspect.MethodInvocation;
import lombok.CustomLog;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Aspect
@CustomLog
public class CacheManager {
    private static final CacheLoader<String, Cache<Object, Object>> loader = new CacheLoader<String, Cache<Object, Object>>() {
        @Nonnull
        @Override
        public Cache<Object, Object> load(@Nonnull String key) {
            return CacheBuilder.newBuilder()
                .expireAfterAccess(4, TimeUnit.HOURS) // TODO: justify
                .build();
        }
    };
    private static final LoadingCache<String, Cache<Object, Object>> caches = CacheBuilder.newBuilder()
        .expireAfterAccess(4, TimeUnit.HOURS) // TODO: justify
        .build(loader);

    private static final Map<Class<?>, Object> histories = new ConcurrentHashMap<>();
    private static final Map<String, Object> namedHistories = new ConcurrentHashMap<>();

    @Pointcut("execution(@com.microsoft.azure.toolkit.lib.common.cache.Cacheable * *..*.*(..))")
    public void cacheable() {
    }

    @Pointcut("execution(@com.microsoft.azure.toolkit.lib.common.cache.CacheEvict * *..*.*(..))")
    public void cacheEvict() {
    }

    @Around("cacheable()")
    public Object aroundCacheable(@Nonnull final ProceedingJoinPoint point) throws Throwable {
        final MethodSignature signature = (MethodSignature) point.getSignature();
        final MethodInvocation invocation = MethodInvocation.from(point);
        final Cacheable annotation = signature.getMethod().getAnnotation(Cacheable.class);

        final String cacheName = StringUtils.firstNonBlank(annotation.cacheName(), annotation.value());
        final String name = ExpressionUtils.render(cacheName, invocation);
        final String key = ExpressionUtils.render(annotation.key(), invocation);

        if (Objects.isNull(name) || Objects.isNull(key)) {
            log.warn(String.format("invalid @Cacheable on method(%s)", signature.getName()));
            return point.proceed();
        }
        final String condition = annotation.condition();
        final boolean toUseCache = StringUtils.isBlank(condition) || ExpressionUtils.evaluate(condition, invocation, true);
        final Cache<Object, Object> cache = caches.get(name);
        if (toUseCache) {
            log.debug(String.format("loading data from cache[%s.%s] on method[%s]", name, key, signature.getName()));
            return readCache(cache, key, point);
        }
        log.debug(String.format("skipping cache[%s.%s] on method[%s]", name, key, signature.getName()));
        final Object result = point.proceed();
        if (Objects.nonNull(result)) {
            cache.put(key, Optional.of(result));
        }
        return result;
    }

    @Around("cacheEvict()")
    public Object aroundCacheEvict(@Nonnull final ProceedingJoinPoint point) throws Throwable {
        final MethodSignature signature = (MethodSignature) point.getSignature();
        final MethodInvocation invocation = MethodInvocation.from(point);
        final CacheEvict annotation = signature.getMethod().getAnnotation(CacheEvict.class);

        final String cacheName = StringUtils.firstNonBlank(annotation.cacheName(), annotation.value());
        final String name = ExpressionUtils.render(cacheName, invocation);
        final String key = ExpressionUtils.render(annotation.key(), invocation);
        final String condition = annotation.condition();
        final boolean toEvictCache = StringUtils.isBlank(condition) || ExpressionUtils.evaluate(condition, invocation, true);

        if (toEvictCache) {
            log.debug(String.format("evict cache[%s.%s] on method[%s]", name, key, signature.getName()));
            evictCache(name, key);
        }
        return point.proceed();
    }

    public static void evictCache(@Nullable final String name, @Nullable final String key) throws ExecutionException {
        if (StringUtils.isBlank(name)) {
            log.warn("cache name is not specified when invalidating cache");
        } else if (StringUtils.equals(CacheEvict.ALL, name)) { // invalidate all cache entries if cache name not specified
            log.debug("invalidate all caches");
            caches.invalidateAll();
        } else {
            if (StringUtils.isBlank(key)) {
                log.warn(String.format("key is not specified when invalidating cache[%s]", name));
            } else if (StringUtils.equals(CacheEvict.ALL, key)) { // invalidate all cache entries of named cache if only cache name is specified
                log.debug(String.format("invalidate all entries in cache[%s]", name));
                caches.invalidate(name);
            } else { // invalidate key specified cache entry of named cache if both cache name and key are specified
                log.debug(String.format("invalidate cache entry[%s.%s]", name, key));
                caches.get(name).invalidate(key);
            }
        }
    }

    private Object readCache(Cache<Object, Object> cache, String key, ProceedingJoinPoint point) throws Throwable {
        final Optional<?> result = (Optional<?>) cache.get(key, () -> {
            try {
                log.debug(String.format("cache[%s] miss on method[%s]", key, point.getSignature().getName()));
                return Optional.ofNullable(point.proceed());
            } catch (final Throwable throwable) {
                log.debug(String.format("error occurs on loading data into cache[%s] on method[%s]", key, point.getSignature().getName()), throwable);
                return Optional.of(throwable);
            }
        });
        if (result.isPresent() && result.get() instanceof Throwable) {
            cache.invalidate(key);
            throw (Throwable) result.get();
        } else {
            return result.orElse(null);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> LRUStack<T> getUsageHistory(final Class<T> clazz) {
        if (histories.containsKey(clazz)) {
            return (LRUStack<T>) histories.get(clazz);
        } else {
            final Optional<Map.Entry<Class<?>, Object>> found = histories.entrySet().stream().filter(e -> e.getKey().isAssignableFrom(clazz) || clazz.isAssignableFrom(e.getKey())).findFirst();
            if (found.isPresent()) {
                return (LRUStack<T>) found.get().getValue();
            } else {
                return (LRUStack<T>) histories.computeIfAbsent(clazz, c -> new LRUStack<>());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> LRUStack<T> getUsageHistory(final String name) {
        return (LRUStack<T>) namedHistories.computeIfAbsent(name, n -> new LRUStack<>());
    }
}
