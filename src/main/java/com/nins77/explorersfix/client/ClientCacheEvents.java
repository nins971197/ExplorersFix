package com.nins77.explorersfix.client;

import com.nins77.explorersfix.ExplorerFix;
import com.nins77.explorersfix.names.OfflineNameGenerator;
import com.nins77.explorersfix.names.StructureNameResolver;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = ExplorerFix.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientCacheEvents {
    private static final SimplePreparableReloadListener<Void> LANGUAGE_RELOAD =
            new SimplePreparableReloadListener<>() {
                @Override
                protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                    return null;
                }

                @Override
                protected void apply(Void value, ResourceManager resourceManager, ProfilerFiller profiler) {
                    clearCaches();
                }
            };

    private ClientCacheEvents() {
    }

    @SubscribeEvent
    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(LANGUAGE_RELOAD);
    }

    @SubscribeEvent
    public static void configReload(ModConfigEvent.Reloading event) {
        clearCaches();
    }

    private static void clearCaches() {
        StructureNameResolver.clearCaches();
        OfflineNameGenerator.clearCache();
        StructureDisplayCache.clear();
    }
}
