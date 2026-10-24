package com.nins77.explorersfix.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ExplorerFixConfig {
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.BooleanValue SHOW_ENGLISH;
    private static final ForgeConfigSpec.BooleanValue AUTO_GENERATE_NAMES;
    private static final ForgeConfigSpec.IntValue MINIMUM_CONFIDENCE;
    private static final ForgeConfigSpec.BooleanValue LOG_MISSING;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("general");

        ENABLED = builder
                .comment("Enable the structure name override.")
                .define("enabled", true);

        SHOW_ENGLISH = builder
                .comment("Append the English name after the Chinese name.")
                .define("showEnglish", true);

        AUTO_GENERATE_NAMES = builder
                .comment("Generate a Chinese name offline when no official translation is available.")
                .define("autoGenerateNames", true);

        MINIMUM_CONFIDENCE = builder
                .comment("Minimum evidence score to use a generated Chinese name (0-100).")
                .defineInRange("minimumConfidence", 40, 0, 100);

        LOG_MISSING = builder
                .comment("Log structures that have no Chinese name mapping.")
                .define("logMissing", false);

        builder.pop();
        SPEC = builder.build();
    }

    private ExplorerFixConfig() {
    }

    public static boolean enabled() {
        return safeGet(ENABLED, true);
    }

    public static boolean showEnglish() {
        return safeGet(SHOW_ENGLISH, true);
    }

    public static boolean autoGenerateNames() {
        return safeGet(AUTO_GENERATE_NAMES, true);
    }

    public static int minimumConfidence() {
        return safeGet(MINIMUM_CONFIDENCE, 40);
    }

    public static boolean logMissing() {
        return safeGet(LOG_MISSING, false);
    }

    private static boolean safeGet(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            return fallback;
        }
    }

    private static int safeGet(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            return fallback;
        }
    }
}
