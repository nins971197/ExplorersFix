package com.nins77.explorersfix.mixin;

import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.nins77.explorersfix.client.StructureDisplayCache;
import com.nins77.explorersfix.config.ExplorerFixConfig;
import com.nins77.explorersfix.names.StructureNameResolver;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@OnlyIn(Dist.CLIENT)
@Mixin(value = StructureUtils.class, remap = false)
public abstract class StructureUtilsMixin {
    @Inject(
            method = "getPrettyStructureName(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false
    )
    private static void explorersfix$appendChineseName(
            ResourceLocation structureId,
            CallbackInfoReturnable<String> callback
    ) {
        if (!ExplorerFixConfig.translationEnabled()) {
            return;
        }
        String original = callback.getReturnValue();
        String resolved = StructureNameResolver.resolve(structureId, original);
        if (resolved != null && !resolved.equals(original)) {
            callback.setReturnValue(resolved);
        }
    }

    @Inject(
            method = "getPrettyStructureSource(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void explorersfix$cachedSource(
            ResourceLocation structureId,
            CallbackInfoReturnable<String> callback
    ) {
        if (structureId == null) {
            return;
        }
        String cached = StructureDisplayCache.source(structureId);
        if (cached != null) {
            callback.setReturnValue(cached);
            callback.cancel();
        }
    }

    @Inject(
            method = "getPrettyStructureSource(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;",
            at = @At("RETURN"),
            remap = false
    )
    private static void explorersfix$storeSource(
            ResourceLocation structureId,
            CallbackInfoReturnable<String> callback
    ) {
        StructureDisplayCache.putSource(structureId, callback.getReturnValue());
    }

    @Inject(
            method = "dimensionKeysToString(Ljava/util/List;)Ljava/lang/String;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void explorersfix$cachedDimensions(
            List<ResourceLocation> dimensions,
            CallbackInfoReturnable<String> callback
    ) {
        if (dimensions == null) {
            return;
        }
        String cached = StructureDisplayCache.dimensions(dimensions);
        if (cached != null) {
            callback.setReturnValue(cached);
            callback.cancel();
        }
    }

    @Inject(
            method = "dimensionKeysToString(Ljava/util/List;)Ljava/lang/String;",
            at = @At("RETURN"),
            remap = false
    )
    private static void explorersfix$storeDimensions(
            List<ResourceLocation> dimensions,
            CallbackInfoReturnable<String> callback
    ) {
        StructureDisplayCache.putDimensions(dimensions, callback.getReturnValue());
    }
}
