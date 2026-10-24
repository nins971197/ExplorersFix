package com.nins77.explorersfix.server;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-side caches for registry data that is stable for a ServerLevel.
 * Entries are weakly keyed by level so world reloads do not leak them.
 */
public final class StructureServerCache {
    private static final Map<ServerLevel, LevelCache> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private StructureServerCache() {
    }

    public static Map<ResourceLocation, ResourceLocation> typeKeys(ServerLevel level) {
        LevelCache cache = get(level);
        return cache == null ? null : cache.typeKeys;
    }

    public static void putTypeKeys(
            ServerLevel level,
            Map<ResourceLocation, ResourceLocation> value
    ) {
        getOrCreate(level).typeKeys = Map.copyOf(value);
    }

    public static List<ResourceLocation> allowed(
            ServerLevel level,
            List<String> blacklist
    ) {
        LevelCache cache = get(level);
        if (cache == null || !cache.blacklist.equals(blacklist)) {
            return null;
        }
        return cache.allowed;
    }

    public static void putAllowed(
            ServerLevel level,
            List<String> blacklist,
            List<ResourceLocation> value
    ) {
        LevelCache cache = getOrCreate(level);
        cache.blacklist = List.copyOf(blacklist);
        cache.allowed = List.copyOf(value);
    }

    public static ListMultimap<ResourceLocation, ResourceLocation> dimensions(
            ServerLevel level,
            List<String> blacklist
    ) {
        LevelCache cache = get(level);
        if (cache == null || !cache.blacklist.equals(blacklist)) {
            return null;
        }
        return cache.dimensions;
    }

    public static void putDimensions(
            ServerLevel level,
            List<String> blacklist,
            ListMultimap<ResourceLocation, ResourceLocation> value
    ) {
        LevelCache cache = getOrCreate(level);
        cache.blacklist = List.copyOf(blacklist);
        cache.dimensions = ArrayListMultimap.create(value);
    }

    public static Map<ResourceLocation, Integer> xpLevels(
            ServerLevel level,
            int defaultLevels,
            List<String> overrides
    ) {
        LevelCache cache = get(level);
        if (cache == null
                || cache.defaultXp == null
                || cache.defaultXp != defaultLevels
                || !cache.xpOverrides.equals(overrides)) {
            return null;
        }
        return cache.xpLevels;
    }

    public static void putXpLevels(
            ServerLevel level,
            int defaultLevels,
            List<String> overrides,
            Map<ResourceLocation, Integer> value
    ) {
        LevelCache cache = getOrCreate(level);
        cache.defaultXp = defaultLevels;
        cache.xpOverrides = List.copyOf(overrides);
        cache.xpLevels = Map.copyOf(value);
    }

    private static LevelCache get(ServerLevel level) {
        if (level == null) {
            return null;
        }
        synchronized (CACHE) {
            LevelCache cache = CACHE.get(level);
            if (cache != null && cache.registry != level.registryAccess()) {
                CACHE.remove(level);
                return null;
            }
            return cache;
        }
    }

    private static LevelCache getOrCreate(ServerLevel level) {
        synchronized (CACHE) {
            LevelCache cache = CACHE.get(level);
            if (cache != null && cache.registry != level.registryAccess()) {
                CACHE.remove(level);
                cache = null;
            }
            if (cache == null) {
                cache = new LevelCache();
                cache.registry = level.registryAccess();
                CACHE.put(level, cache);
            }
            return cache;
        }
    }

    private static final class LevelCache {
        private RegistryAccess registry;
        private Map<ResourceLocation, ResourceLocation> typeKeys;
        private List<String> blacklist = List.of();
        private List<ResourceLocation> allowed;
        private ListMultimap<ResourceLocation, ResourceLocation> dimensions;
        private Integer defaultXp;
        private List<String> xpOverrides = List.of();
        private Map<ResourceLocation, Integer> xpLevels;
    }
}
