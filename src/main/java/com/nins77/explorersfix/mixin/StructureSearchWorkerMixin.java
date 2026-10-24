package com.nins77.explorersfix.mixin;

import com.chaosthedude.explorerscompass.worker.StructureSearchWorker;
import com.mojang.datafixers.util.Pair;
import com.nins77.explorersfix.server.StructureSearchWorkerCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = StructureSearchWorker.class, remap = false)
public abstract class StructureSearchWorkerMixin {
    @Inject(
            method = "getStructureGeneratingAt(Lnet/minecraft/world/level/ChunkPos;)Lcom/mojang/datafixers/util/Pair;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void explorersfix$cachedCheck(
            ChunkPos chunkPos,
            CallbackInfoReturnable<Pair<BlockPos, Structure>> callback
    ) {
        Pair<BlockPos, Structure> cached =
                StructureSearchWorkerCache.get(this, chunkPos.toLong());
        if (cached != null) {
            callback.setReturnValue(cached);
            callback.cancel();
        }
    }

    @Inject(
            method = "getStructureGeneratingAt(Lnet/minecraft/world/level/ChunkPos;)Lcom/mojang/datafixers/util/Pair;",
            at = @At("RETURN"),
            remap = false
    )
    private void explorersfix$storeCheck(
            ChunkPos chunkPos,
            CallbackInfoReturnable<Pair<BlockPos, Structure>> callback
    ) {
        if (callback.getReturnValue() != null) {
            StructureSearchWorkerCache.put(this, chunkPos.toLong(), callback.getReturnValue());
        }
    }

    @Inject(method = "stop", at = @At("HEAD"), remap = false)
    private void explorersfix$clearOnStop(CallbackInfo callback) {
        StructureSearchWorkerCache.clear(this);
    }

    @Inject(
            method = "succeed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void explorersfix$clearOnSucceed(
            BlockPos pos,
            Structure structure,
            CallbackInfo callback
    ) {
        StructureSearchWorkerCache.clear(this);
    }

    @Inject(method = "fail", at = @At("HEAD"), remap = false)
    private void explorersfix$clearOnFail(CallbackInfo callback) {
        StructureSearchWorkerCache.clear(this);
    }
}
