package com.nins77.explorersfix.mixin;

import com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen;
import com.chaosthedude.explorerscompass.gui.StructureSearchList;
import com.chaosthedude.explorerscompass.gui.TransparentTextField;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.nins77.explorersfix.config.ExplorerFixConfig;
import com.nins77.explorersfix.names.StructureNameResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@OnlyIn(Dist.CLIENT)
@Mixin(value = ExplorersCompassScreen.class, remap = false)
public abstract class StructureSearchMixin {
    private static final ScheduledExecutorService SEARCH_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ExplorerFix-SearchDebounce");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    private static final ThreadLocal<Boolean> IMMEDIATE = ThreadLocal.withInitial(() -> false);

    @Shadow
    private List<ResourceLocation> allowedStructureKeys;

    @Shadow
    private List<ResourceLocation> structureKeysMatchingSearch;

    @Shadow
    private TransparentTextField searchTextField;

    @Shadow
    private StructureSearchList selectionList;

    @Inject(method = "processSearchTerm", at = @At("HEAD"), cancellable = true, remap = false)
    private void explorersfix$searchAliases(CallbackInfo callback) {
        if (IMMEDIATE.get()) {
            runSearch(callback);
            return;
        }

        String requested = searchTextField.getValue();
        SEARCH_SCHEDULER.schedule(() -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != (Object) this
                    || !requested.equals(searchTextField.getValue())) {
                return;
            }
            IMMEDIATE.set(true);
            try {
                ((ExplorersCompassScreen) (Object) this).processSearchTerm();
            } finally {
                IMMEDIATE.remove();
            }
        }), 60L, TimeUnit.MILLISECONDS);
        callback.cancel();
    }

    private void runSearch(CallbackInfo callback) {
        String term = searchTextField.getValue().toLowerCase(Locale.ROOT);
        structureKeysMatchingSearch.clear();

        for (ResourceLocation key : allowedStructureKeys) {
            if (term.startsWith("@")) {
                String source = StructureUtils.getPrettyStructureSource(key).toLowerCase(Locale.ROOT);
                if (source.contains(term.substring(1))) {
                    structureKeysMatchingSearch.add(key);
                }
            } else {
                boolean matches = ExplorerFixConfig.translationEnabled()
                        ? StructureNameResolver.matchesSearch(key, term)
                        : StructureUtils.getPrettyStructureName(key).toLowerCase(Locale.ROOT).contains(term);
                if (matches) {
                    structureKeysMatchingSearch.add(key);
                }
            }
        }

        selectionList.refreshList(true);
        callback.cancel();
    }
}
