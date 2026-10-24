package com.nins77.explorersfix.client;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side caches for values that Explorer's Compass rebuilds every frame. */
public final class StructureDisplayCache {
    private static final Map<String, String> SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, String> DIMENSIONS = new ConcurrentHashMap<>();

    private StructureDisplayCache() {
    }

    public static String source(ResourceLocation id) {
        return SOURCES.get(id.toString());
    }

    public static void putSource(ResourceLocation id, String value) {
        if (id != null && value != null) {
            SOURCES.put(id.toString(), value);
        }
    }

    public static String dimensions(List<ResourceLocation> ids) {
        return DIMENSIONS.get(dimensionKey(ids));
    }

    public static void putDimensions(List<ResourceLocation> ids, String value) {
        if (ids != null && value != null) {
            DIMENSIONS.put(dimensionKey(ids), value);
        }
    }

    public static void clear() {
        SOURCES.clear();
        DIMENSIONS.clear();
    }

    private static String dimensionKey(List<ResourceLocation> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            values.add(id.toString());
        }
        values.sort(Comparator.naturalOrder());
        return String.join("|", values);
    }
}
