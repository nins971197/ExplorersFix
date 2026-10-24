package com.nins77.explorersfix.names;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nins77.explorersfix.ExplorerFix;
import com.nins77.explorersfix.config.ExplorerFixConfig;
import com.nins77.explorersfix.names.OfflineTranslationIndex.StructureHints;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class StructureNameResolver {
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");
    private static final Object EXTERNAL_LOCK = new Object();
    private static final Map<String, CachedResolution> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> ORIGINAL_NAMES = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_MISSING = ConcurrentHashMap.newKeySet();

    private static volatile Map<String, NameEntry> external = Map.of();
    private static volatile boolean externalLoaded;
    private static volatile long externalLastModified = Long.MIN_VALUE;
    private static volatile long nextExternalCheck;
    private static volatile long cacheGeneration = -1;

    private StructureNameResolver() {
    }

    public static String resolve(ResourceLocation structureId, String original) {
        if (structureId == null || !ExplorerFixConfig.translationEnabled()) {
            return original;
        }

        OfflineTranslationIndex.startLoading();
        ensureExternalLoaded();
        refreshCacheGeneration();
        if (original != null) {
            String previous = ORIGINAL_NAMES.put(structureId.toString(), original);
            if (previous != null && !previous.equals(original)) {
                CACHE.remove(structureId.toString());
            }
        }
        return getResolution(structureId, original).display();
    }

    public static boolean matchesSearch(ResourceLocation structureId, String searchTerm) {
        if (structureId == null) {
            return false;
        }
        if (!ExplorerFixConfig.translationEnabled()) {
            return false;
        }
        if (searchTerm == null || searchTerm.isBlank()) {
            return true;
        }

        OfflineTranslationIndex.startLoading();
        ensureExternalLoaded();
        refreshCacheGeneration();
        String term = searchTerm.toLowerCase(Locale.ROOT);
        NameResolution resolution = getResolution(structureId, ORIGINAL_NAMES.get(structureId.toString()));
        return resolution.searchText().contains(term)
                || structureId.toString().toLowerCase(Locale.ROOT).contains(term)
                || structureId.getPath().replace('_', ' ').toLowerCase(Locale.ROOT).contains(term);
    }

    public static void clearCaches() {
        CACHE.clear();
        LOGGED_MISSING.clear();
    }

    private static NameResolution getResolution(ResourceLocation structureId, String original) {
        String id = structureId.toString();
        long generation = OfflineTranslationIndex.generation();
        CachedResolution cached = CACHE.get(id);
        if (cached != null && cached.generation() == generation) {
            return cached.resolution();
        }

        NameResolution resolution = buildResolution(structureId, original);
        CACHE.put(id, new CachedResolution(generation, resolution));
        return resolution;
    }

    private static NameResolution buildResolution(ResourceLocation structureId, String original) {
        String id = structureId.toString();
        String namespace = structureId.getNamespace();
        String path = structureId.getPath();
        String standardKey = "structure." + namespace + "." + path;
        Map<String, NameCandidate> candidates = new LinkedHashMap<>();
        Set<String> aliases = new LinkedHashSet<>();
        String english = null;

        // 1. Explicit pack/user override.
        NameEntry explicit = external.get(id);
        if (explicit != null) {
            String chinese = blankToNull(explicit.zh());
            if (explicit.key() != null) {
                String fromKey = lookupChinese(explicit.key());
                if (fromKey != null && chinese == null) {
                    chinese = fromKey;
                }
                if (english == null) {
                    english = lookupEnglish(explicit.key());
                }
            }
            if (chinese != null) {
                chinese = emptyIfNull(explicit.prefix()) + chinese + emptyIfNull(explicit.suffix());
                addCandidate(candidates, aliases, chinese, 100, "explicit_override");
            }
            if (english == null) {
                english = blankToNull(explicit.en());
            }
        }

        // 2. The mod's own structure translation is the strongest deterministic source.
        String standardChinese = lookupChinese(standardKey);
        if (standardChinese != null) {
            addCandidate(candidates, aliases, standardChinese, 100, "structure_key");
        } else if (english == null) {
            english = lookupEnglish(standardKey);
        }

        // 3. Evidence from map items, advancements, entities and biomes.
        String[] evidenceKeys = {
                "filled_map." + namespace + "." + path,
                "item." + namespace + "." + path + "_map",
                "advancements." + namespace + ".discover_" + path + ".title",
                "advancements." + namespace + "." + path + ".title",
                "advancements." + namespace + ".find_" + path + ".title",
                "entity." + namespace + "." + path,
                "biome." + namespace + "." + path
        };
        int[] evidenceScores = {95, 92, 82, 78, 78, 65, 60};
        boolean[] mapLike = {true, true, false, false, false, false, false};
        String[] sources = {
                "filled_map", "item_map", "advancement_discover",
                "advancement", "advancement_find", "entity", "biome"
        };
        for (int index = 0; index < evidenceKeys.length; index++) {
            String chinese = lookupChinese(evidenceKeys[index]);
            if (chinese != null) {
                chinese = cleanOfficialValue(chinese, mapLike[index]);
                if (!chinese.isBlank()) {
                    addCandidate(candidates, aliases, chinese, evidenceScores[index], sources[index]);
                }
            }
            if (english == null) {
                String fromKey = lookupEnglish(evidenceKeys[index]);
                if (fromKey != null) {
                    english = cleanOfficialValue(fromKey, mapLike[index]);
                }
            }
        }

        // The local I18n resource pack and several mods use alternate key
        // layouts such as filled_map.<ns>:<path> and
        // worldgen.structure.<ns>.<path>. Map those keys back to this ID.
        for (String aliasKey : OfflineTranslationIndex.getKeysForStructure(namespace, path)) {
            String chinese = lookupChinese(aliasKey);
            if (chinese != null) {
                chinese = cleanOfficialValue(chinese, isMapLikeKey(aliasKey));
                if (!chinese.isBlank()) {
                    addCandidate(candidates, aliases, chinese, scoreForAliasKey(aliasKey), sourceForAliasKey(aliasKey));
                }
            }
            if (english == null) {
                String fromKey = lookupEnglish(aliasKey);
                if (fromKey != null) {
                    english = cleanOfficialValue(fromKey, isMapLikeKey(aliasKey));
                }
            }
        }

        // 4. Another compatibility layer may already provide a Chinese result.
        if (original != null && containsCjk(original)) {
            addCandidate(candidates, aliases, original, 70, "original_result");
        }

        // 5. Exact English phrase from the offline translation memory.
        String phrase = prettyEnglish(structureId);
        String phraseChinese = OfflineTranslationIndex.getPhrase(namespace, phrase);
        if (phraseChinese != null) {
            addCandidate(candidates, aliases, cleanPhraseValue(phraseChinese), 55, "translation_memory");
        }
        String spacedPath = path.replace('/', ' ').replace('_', ' ');
        if (!spacedPath.equalsIgnoreCase(phrase)) {
            String pathPhrase = OfflineTranslationIndex.getPhrase(namespace, spacedPath);
            if (pathPhrase != null) {
                addCandidate(candidates, aliases, cleanPhraseValue(pathPhrase), 52, "translation_memory_path");
            }
        }

        // 6. Offline composition. Unknown proper nouns return null; the
        // resolver then keeps the English name instead of transliterating it.
        if (ExplorerFixConfig.autoGenerateNames()) {
            StructureHints hints = OfflineTranslationIndex.getHints(id);
            String generated = OfflineNameGenerator.generate(namespace, path, hints);
            if (generated != null) {
                addCandidate(candidates, aliases, generated, 40, "offline_dictionary");
            }
        }

        if (english == null && original != null && !containsCjk(original) && !standardKey.equals(original)) {
            english = original;
        }
        if (english == null) {
            english = prettyEnglish(structureId);
        }

        NameCandidate best = null;
        for (NameCandidate candidate : candidates.values()) {
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }

        if (best != null && best.score() < ExplorerFixConfig.minimumConfidence()) {
            best = null;
        }
        if (best != null && best.score() < 70) {
            int topScore = best.score();
            long topCount = candidates.values().stream()
                    .filter(candidate -> candidate.score() == topScore)
                    .count();
            if (topCount > 1) {
                best = null;
            }
        }

        if (best == null) {
            if (ExplorerFixConfig.logMissing() && LOGGED_MISSING.add(id)) {
                ExplorerFix.LOGGER.info("No Chinese structure name for {} (English: {})", id, english);
            }
            aliases.add(english);
            aliases.add(id);
            aliases.add(path);
            List<String> aliasList = List.copyOf(aliases);
            return new NameResolution(
                    original != null && !original.isBlank() ? original : english,
                    null,
                    english,
                    aliasList,
                    "none",
                    0,
                    searchText(aliasList)
            );
        }

        aliases.add(best.chinese());
        aliases.add(english);
        aliases.add(id);
        aliases.add(path);
        if (original != null && !original.isBlank()) {
            aliases.add(original);
        }

        String display = best.chinese();
        if (ExplorerFixConfig.showEnglish()
                && english != null
                && !english.isBlank()
                && !best.chinese().equalsIgnoreCase(english)
                && !best.chinese().contains(english)) {
            display = best.chinese() + " (" + english + ")";
        }
        aliases.add(display);
        List<String> aliasList = List.copyOf(aliases);

        return new NameResolution(
                display,
                best.chinese(),
                english,
                aliasList,
                best.source(),
                best.score(),
                searchText(aliasList)
        );
    }

    private static String searchText(List<String> aliases) {
        StringBuilder result = new StringBuilder();
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(alias.toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static void addCandidate(
            Map<String, NameCandidate> candidates,
            Set<String> aliases,
            String chinese,
            int score,
            String source
    ) {
        if (chinese == null) {
            return;
        }
        String value = chinese.trim();
        if (value.isBlank() || !containsCjk(value) || value.indexOf('%') >= 0) {
            return;
        }
        aliases.add(value);
        NameCandidate existing = candidates.get(value);
        if (existing == null || score > existing.score()) {
            candidates.put(value, new NameCandidate(value, source, score));
        }
    }

    private static String lookupChinese(String key) {
        if (key == null) {
            return null;
        }
        String value = I18n.get(key);
        if (value != null && !key.equals(value) && containsCjk(value)) {
            return value;
        }
        String indexed = OfflineTranslationIndex.getChinese(key);
        return indexed != null && containsCjk(indexed) ? indexed : null;
    }

    private static String lookupEnglish(String key) {
        if (key == null) {
            return null;
        }
        String value = I18n.get(key);
        if (value != null && !key.equals(value) && !containsCjk(value)) {
            return value;
        }
        String indexed = OfflineTranslationIndex.getEnglish(key);
        return indexed != null && !containsCjk(indexed) ? indexed : null;
    }

    private static int scoreForAliasKey(String key) {
        if (key.startsWith("structure.") || key.startsWith("worldgen.structure.")) {
            return 100;
        }
        if (key.startsWith("filled_map.")) {
            return 95;
        }
        if (key.startsWith("item.")) {
            return 92;
        }
        if (key.startsWith("advancements.")) {
            return 80;
        }
        if (key.startsWith("entity.")) {
            return 65;
        }
        if (key.startsWith("biome.")) {
            return 60;
        }
        return 50;
    }

    private static String sourceForAliasKey(String key) {
        if (key.startsWith("worldgen.structure.")) {
            return "worldgen_structure_key";
        }
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) + "_alias" : "alias";
    }

    private static boolean isMapLikeKey(String key) {
        return key.startsWith("filled_map.") || (key.startsWith("item.") && key.endsWith("_map"));
    }

    private static String cleanOfficialValue(String value, boolean mapLike) {
        String cleaned = value.trim();
        if (mapLike) {
            cleaned = removeSuffix(cleaned, "探险家地图");
            cleaned = removeSuffix(cleaned, "Explorer's Map");
            cleaned = removeSuffix(cleaned, "Explorer Map");
            cleaned = removeSuffix(cleaned, "地图");
            cleaned = removeSuffix(cleaned, " Map");
            cleaned = removeSuffix(cleaned, "map");
        }
        cleaned = removeSuffix(cleaned, "刷怪蛋");
        cleaned = removeSuffix(cleaned, "Spawn Egg");
        return cleaned.replaceAll("^[\\s:：\\-]+", "").replaceAll("[\\s:：\\-]+$", "").trim();
    }

    private static String cleanPhraseValue(String value) {
        String cleaned = value.trim();
        cleaned = removeSuffix(cleaned, "探险家地图");
        cleaned = removeSuffix(cleaned, "Explorer's Map");
        cleaned = removeSuffix(cleaned, "Explorer Map");
        return cleaned.trim();
    }

    private static String removeSuffix(String value, String suffix) {
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length()).trim();
        }
        return value;
    }

    private static void refreshCacheGeneration() {
        long generation = OfflineTranslationIndex.generation();
        if (generation != cacheGeneration) {
            cacheGeneration = generation;
            CACHE.clear();
            OfflineNameGenerator.clearCache();
        }
    }

    private static void ensureExternalLoaded() {
        if (!externalLoaded) {
            synchronized (EXTERNAL_LOCK) {
                if (!externalLoaded) {
                    loadExternal(true);
                    externalLoaded = true;
                }
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= nextExternalCheck) {
            nextExternalCheck = now + 1000L;
            synchronized (EXTERNAL_LOCK) {
                loadExternal(false);
            }
        }
    }

    private static void loadExternal(boolean createTemplate) {
        Path file = FMLPaths.CONFIGDIR.get().resolve("explorersfix").resolve("names.json");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                if (createTemplate) {
                    Files.writeString(file, EXTERNAL_TEMPLATE, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW);
                } else {
                    return;
                }
            }

            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == externalLastModified) {
                return;
            }
            externalLastModified = modified;

            Map<String, NameEntry> replacement = new LinkedHashMap<>();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                readInto(JsonParser.parseReader(reader).getAsJsonObject(), replacement);
            }
            external = Map.copyOf(replacement);
            CACHE.clear();
        } catch (Exception exception) {
            ExplorerFix.LOGGER.error("Failed to load {}", file, exception);
        }
    }

    private static void readInto(JsonObject root, Map<String, NameEntry> target) {
        for (Map.Entry<String, JsonElement> item : root.entrySet()) {
            String id = item.getKey();
            if (id.startsWith("_")) {
                continue;
            }

            JsonElement value = item.getValue();
            if (value.isJsonPrimitive()) {
                target.put(id, new NameEntry(value.getAsString(), null, null, "", ""));
                continue;
            }
            if (!value.isJsonObject()) {
                continue;
            }

            JsonObject object = value.getAsJsonObject();
            String zh = stringOrNull(object, "zh");
            if (zh == null) {
                zh = stringOrNull(object, "text");
            }
            target.put(id, new NameEntry(
                    zh,
                    stringOrNull(object, "en"),
                    stringOrNull(object, "key"),
                    emptyIfNull(stringOrNull(object, "prefix")),
                    emptyIfNull(stringOrNull(object, "suffix"))
            ));
        }
    }

    private static String stringOrNull(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        String value = object.get(name).getAsString();
        return value.isBlank() ? null : value;
    }

    private static String prettyEnglish(ResourceLocation id) {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            path = path.substring(slash + 1);
        }

        StringBuilder builder = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private static boolean containsCjk(String value) {
        return value != null && CJK.matcher(value).find();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    public record NameResolution(
            String display,
            String chinese,
            String english,
            List<String> aliases,
            String source,
            int confidence,
            String searchText
    ) {
    }

    private record NameEntry(String zh, String en, String key, String prefix, String suffix) {
    }

    private record NameCandidate(String chinese, String source, int score) {
    }

    private record CachedResolution(long generation, NameResolution resolution) {
    }

    private static final String EXTERNAL_TEMPLATE = """
            {
              "_comment": "Format: \\"namespace:structure_path\\": {\\"zh\\": \\"中文名\\", \\"en\\": \\"English Name\\"}",
              "_alias_example": "alexscaves:licowitch_tower: {\\"key\\": \\"entity.alexscaves.licowitch\\", \\"suffix\\": \\"塔\\", \\"en\\": \\"Licowitch Tower\\"}",
              "example_mod:example_structure": {
                "zh": "示例结构",
                "en": "Example Structure"
              }
            }
            """;
}
