package com.nins77.explorersfix;

import com.nins77.explorersfix.config.ExplorerFixConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ExplorerFix.MOD_ID)
public final class ExplorerFix {
    public static final String MOD_ID = "explorersfix";
    public static final String MOD_NAME = "Explorer's Fix";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public ExplorerFix() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ExplorerFixConfig.SPEC);
    }
}
