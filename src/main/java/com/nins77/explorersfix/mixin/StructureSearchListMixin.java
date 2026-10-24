package com.nins77.explorersfix.mixin;

import com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen;
import com.chaosthedude.explorerscompass.gui.StructureSearchEntry;
import com.chaosthedude.explorerscompass.gui.StructureSearchList;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
@Mixin(value = StructureSearchList.class, remap = false)
public abstract class StructureSearchListMixin {
    @Shadow
    private ExplorersCompassScreen parentScreen;

    @Shadow
    protected abstract void clearEntries();

    @Shadow
    protected abstract int addEntry(StructureSearchEntry entry);

    @Shadow
    public abstract void setSelected(StructureSearchEntry entry);

    @Shadow
    public abstract void setScrollAmount(double amount);

    @Unique
    private Map<ResourceLocation, StructureSearchEntry> explorersfix$entryCache = new HashMap<>();

    @Inject(
            method = "refreshList(Lnet/minecraft/resources/ResourceLocation;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void explorersfix$reuseEntries(
            ResourceLocation structureKeyToSelect,
            CallbackInfo callback
    ) {
        clearEntries();
        for (ResourceLocation key : parentScreen.sortStructures()) {
            StructureSearchEntry entry = explorersfix$entryCache.computeIfAbsent(
                    key,
                    id -> new StructureSearchEntry(
                            (StructureSearchList) (Object) this,
                            id,
                            parentScreen.getPlayer()
                    )
            );
            addEntry(entry);
            if (key.equals(structureKeyToSelect)) {
                setSelected(entry);
            }
        }
        setScrollAmount(0);
        callback.cancel();
    }
}
