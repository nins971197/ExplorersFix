package com.nins77.explorersfix.mixin;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.google.common.collect.ListMultimap;
import com.nins77.explorersfix.server.StructureServerCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(value = StructureUtils.class, remap = false)
public abstract class StructureServerCacheMixin {
    @Inject(
            method = "getStructureKeysToTypeKeys(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/Map;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void explorersfix$cachedTypeKeys(
            ServerLevel level,
            CallbackInfoReturnable<Map<ResourceLocation, ResourceLocation>> callback
    ) {
        Map<ResourceLocation, ResourceLocation> cached = StructureServerCache.typeKeys(level);
        if (cached != null) {
            callback.setReturnValue(cached);
            callback.cancel();
        }
    }

    @Inject(
            method = "getStructureKeysToTypeKeys(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/Map;",
            at = @At("RETURN"),
            remap = false
    )
    private static void explorersfix$storeTypeKeys(
            ServerLevel level,
            CallbackInfoReturnable<Map<ResourceLocation, ResourceLocation>> callback
    ) {
        StructureServerCache.putTypeKeys(level, callback.getReturnValue());
    }

    @Inject(
            method = "getAllowedStructureKeys(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void explorersfix$cachedAllowed(
            ServerLevel level,
            CallbackInfoReturnable<List<ResourceLocation>> callback
    ) {
        List<String> blacklist = List.copyOf(ConfigHandler.GENERAL.structureBlacklist.get());
        List<ResourceLocation> cached = StructureServerCache.allowed(level, blacklist);
        if (cached != null) {
            callback.setReturnValue(cached);
            callback.cancel();
        }
    }

    @Inject(
            method = "getAllowedStructureKeys(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/List;",
            at = @At("RETURN"),
            remap = false
    )
    private static void explorersfix$storeAllowed(
            ServerLevel level,
            CallbackInfoReturnable<List<ResourceLocation>> callback
    ) {
        StructureServerCache.putAllowed(
                level,
                List.copyOf(ConfigHandler.GENERAL.structureBlacklist.get()),
                callback.getReturnValue()
        );
    }

    @Inject(
            method = "getGeneratingDimensionsForAllowedStructures(Lnet/minecraft/server/level/ServerLevel;)Lcom/google/common/collect/ListMultimap;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void explorersfix$cachedDimensions(
            ServerLevel level,
            CallbackInfoReturnable<ListMultimap<ResourceLocation, ResourceLocation>> callback
    ) {
        ListMultimap<ResourceLocation, ResourceLocation> cached =
                StructureServerCache.dimensions(
                        level,
                        List.copyOf(ConfigHandler.GENERAL.structureBlacklist.get())
                );
        if (cached != null) {
            callback.setReturnValue(cached);
            callback.cancel();
        }
    }

    @Inject(
            method = "getGeneratingDimensionsForAllowedStructures(Lnet/minecraft/server/level/ServerLevel;)Lcom/google/common/collect/ListMultimap;",
            at = @At("RETURN"),
            remap = false
    )
    private static void explorersfix$storeDimensions(
            ServerLevel level,
            CallbackInfoReturnable<ListMultimap<ResourceLocation, ResourceLocation>> callback
    ) {
        StructureServerCache.putDimensions(
                level,
                List.copyOf(ConfigHandler.GENERAL.structureBlacklist.get()),
                callback.getReturnValue()
        );
    }

    @Inject(
            method = "getXpLevelsForAllowedStructures(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/Map;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void explorersfix$cachedXpLevels(
            ServerLevel level,
            CallbackInfoReturnable<Map<ResourceLocation, Integer>> callback
    ) {
        Map<ResourceLocation, Integer> cached = StructureServerCache.xpLevels(
                level,
                ConfigHandler.GENERAL.defaultXpLevels.get(),
                List.copyOf(ConfigHandler.GENERAL.perStructureXpLevels.get())
        );
        if (cached != null) {
            callback.setReturnValue(cached);
            callback.cancel();
        }
    }

    @Inject(
            method = "getXpLevelsForAllowedStructures(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/Map;",
            at = @At("RETURN"),
            remap = false
    )
    private static void explorersfix$storeXpLevels(
            ServerLevel level,
            CallbackInfoReturnable<Map<ResourceLocation, Integer>> callback
    ) {
        StructureServerCache.putXpLevels(
                level,
                ConfigHandler.GENERAL.defaultXpLevels.get(),
                List.copyOf(ConfigHandler.GENERAL.perStructureXpLevels.get()),
                callback.getReturnValue()
        );
    }
}
