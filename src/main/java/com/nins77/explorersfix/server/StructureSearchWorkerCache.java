package com.nins77.explorersfix.server;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Bounded per-worker cache for repeated chunk structure checks. */
public final class StructureSearchWorkerCache {
    private static final int MAX_ENTRIES = 4096;
    private static final Map<Object, WorkerCache> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private StructureSearchWorkerCache() {
    }

    public static Pair<BlockPos, Structure> get(Object worker, long chunkKey) {
        synchronized (CACHE) {
            WorkerCache cache = CACHE.get(worker);
            return cache == null ? null : cache.values.get(chunkKey);
        }
    }

    public static void put(
            Object worker,
            long chunkKey,
            Pair<BlockPos, Structure> value
    ) {
        if (value == null) {
            return;
        }
        synchronized (CACHE) {
            CACHE.computeIfAbsent(worker, ignored -> new WorkerCache())
                    .values.put(chunkKey, value);
        }
    }

    public static void clear(Object worker) {
        synchronized (CACHE) {
            CACHE.remove(worker);
        }
    }

    private static final class WorkerCache {
        private final Map<Long, Pair<BlockPos, Structure>> values =
                new LinkedHashMap<>(64, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<Long, Pair<BlockPos, Structure>> eldest
                    ) {
                        return size() > MAX_ENTRIES;
                    }
                };
    }
}
