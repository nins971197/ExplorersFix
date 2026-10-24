package com.nins77.explorersfix.names;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nins77.explorersfix.ExplorerFix;
import com.nins77.explorersfix.names.OfflineTranslationIndex.StructureHints;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Composes a Chinese name only when every meaningful token has a known Chinese
 * term. If a proper noun is unknown, it returns null and the caller keeps the
 * English name instead of inventing a transliteration.
 */
public final class OfflineNameGenerator {
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");
    private static final Map<String, String> WORDS = new HashMap<>();
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> HEAD_TOKENS = Set.of(
            "temple", "shrine", "altar", "church", "cathedral", "monastery",
            "palace", "manor", "mansion", "castle", "fort", "fortress",
            "stronghold", "citadel", "keep", "tower", "spire", "lighthouse",
            "tomb", "crypt", "catacomb", "catacombs", "mausoleum", "graveyard",
            "cemetery", "dungeon", "cave", "cavern", "pit", "trench",
            "canyon", "bridge", "mineshaft", "mine", "quarry", "foundry",
            "factory", "workshop", "blacksmith", "tavern", "inn", "camp",
            "outpost", "settlement", "village", "town", "city", "hamlet",
            "house", "hut", "cabin", "home", "hideaway", "retreat", "ship",
            "wreck", "wreckage", "shipwreck", "vessel", "garden", "grove",
            "orchard", "arena", "coliseum", "acropolis", "monument", "monolith",
            "pillar", "arch", "nest", "den", "lair", "portal", "fossil",
            "ruin", "ruins", "remains"
    );
    private static volatile boolean loaded;

    private OfflineNameGenerator() {
    }

    public static String generate(String namespace, String path, StructureHints hints) {
        String key = namespace + ":" + path;
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        ensureLoaded();
        String generated = compose(namespace, path, hints);
        if (generated == null || generated.isBlank() || !CJK.matcher(generated).find()) {
            return null;
        }
        CACHE.put(key, generated);
        return generated;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static String compose(String namespace, String path, StructureHints hints) {
        String normalized = path.toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.startsWith("exclusives/")) {
            normalized = normalized.substring("exclusives/".length());
        }

        if (normalized.startsWith("village_")) {
            String suffix = translateTokens(namespace, normalized.substring("village_".length()));
            return suffix == null ? null : suffix + "村庄";
        }
        if (normalized.startsWith("pillager_outpost_")) {
            String suffix = translateTokens(namespace, normalized.substring("pillager_outpost_".length()));
            return suffix == null ? null : suffix + "掠夺者前哨站";
        }
        if (normalized.startsWith("wreckage_")) {
            String suffix = translateTokens(namespace, normalized.substring("wreckage_".length()));
            return suffix == null ? null : suffix + "残骸";
        }

        String effectivePath = addSemanticHead(normalized, hints);
        String translated = translateTokens(namespace, effectivePath);
        if (translated == null) {
            return null;
        }
        if ("totw_modded".equals(namespace) && !translated.endsWith("塔")) {
            translated += "之塔";
        }
        return translated;
    }

    private static String addSemanticHead(String path, StructureHints hints) {
        List<String> tokens = split(path);
        for (String token : tokens) {
            if (HEAD_TOKENS.contains(token)) {
                return path;
            }
        }
        String head = hints == null ? null : findHeadHint(hints);
        return head == null ? path : path + "_" + head;
    }

    private static String findHeadHint(StructureHints hints) {
        String startPool = hints.startPool();
        if (startPool != null) {
            int colon = startPool.indexOf(':');
            String value = colon >= 0 ? startPool.substring(colon + 1) : startPool;
            for (String token : split(value)) {
                if (HEAD_TOKENS.contains(token)) {
                    return token;
                }
            }
        }

        String type = hints.type();
        if (type != null) {
            int colon = type.indexOf(':');
            String value = colon >= 0 ? type.substring(colon + 1) : type;
            for (String token : split(value)) {
                if (HEAD_TOKENS.contains(token)) {
                    return token;
                }
            }
        }
        return null;
    }

    private static String translateTokens(String namespace, String path) {
        List<String> tokens = split(path);
        if (tokens.isEmpty()) {
            return "";
        }

        int ofIndex = tokens.indexOf("of");
        if (ofIndex > 0 && ofIndex < tokens.size() - 1) {
            String left = translateTokens(namespace, String.join("_", tokens.subList(0, ofIndex)));
            String right = translateTokens(namespace, String.join("_", tokens.subList(ofIndex + 1, tokens.size())));
            if (left == null || right == null) {
                return null;
            }
            String head = tokens.get(ofIndex - 1);
            return isLinkedHead(head) ? right + "之" + left : right + left;
        }

        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < tokens.size()) {
            boolean matched = false;
            int max = Math.min(4, tokens.size() - index);
            for (int length = max; length >= 2; length--) {
                String phrase = String.join("_", tokens.subList(index, index + length));
                String phraseValue = WORDS.get(phrase);
                if (phraseValue == null) {
                    phraseValue = OfflineTranslationIndex.getTerm(namespace, phrase);
                }
                if (phraseValue != null) {
                    result.append(phraseValue);
                    index += length;
                    matched = true;
                    break;
                }
            }
            if (matched) {
                continue;
            }

            String token = tokens.get(index++);
            if (isStopWord(token)) {
                continue;
            }
            String translated = translateToken(namespace, token);
            if (translated == null) {
                return null;
            }
            result.append(translated);
        }

        return result.length() == 0 ? null : result.toString();
    }

    private static String translateToken(String namespace, String token) {
        String known = WORDS.get(token);
        if (known == null) {
            known = OfflineTranslationIndex.getTerm(namespace, token);
        }
        if (known == null) {
            for (String singular : singularForms(token)) {
                known = WORDS.get(singular);
                if (known == null) {
                    known = OfflineTranslationIndex.getTerm(namespace, singular);
                }
                if (known != null) {
                    break;
                }
            }
        }
        if (known != null) {
            return known;
        }
        if (token.chars().allMatch(Character::isDigit)) {
            StringBuilder digits = new StringBuilder();
            for (int index = 0; index < token.length(); index++) {
                digits.append("零一二三四五六七八九".charAt(token.charAt(index) - '0'));
            }
            return digits.toString();
        }
        return null;
    }

    private static List<String> singularForms(String token) {
        List<String> forms = new ArrayList<>(3);
        if (token.endsWith("ies") && token.length() > 3) {
            forms.add(token.substring(0, token.length() - 3) + "y");
        }
        if (token.endsWith("es") && token.length() > 2) {
            forms.add(token.substring(0, token.length() - 2));
        }
        if (token.endsWith("s") && token.length() > 1) {
            forms.add(token.substring(0, token.length() - 1));
        }
        return forms;
    }

    private static List<String> split(String value) {
        List<String> tokens = new ArrayList<>();
        for (String token : value.split("[_/]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean isLinkedHead(String token) {
        return switch (token) {
            case "tower", "spire", "keep", "fort", "fortress", "castle", "citadel", "stronghold" -> true;
            default -> false;
        };
    }

    private static boolean isStopWord(String token) {
        return switch (token) {
            case "the", "a", "an", "of" -> true;
            default -> false;
        };
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (OfflineNameGenerator.class) {
            if (loaded) {
                return;
            }
            try (InputStream stream = OfflineNameGenerator.class.getResourceAsStream(
                    "/assets/explorersfix/words_zh_cn.json")) {
                if (stream == null) {
                    ExplorerFix.LOGGER.warn("Offline word table is missing");
                } else {
                    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                            if (!entry.getKey().startsWith("_") && entry.getValue().isJsonPrimitive()) {
                                WORDS.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }
                    }
                }
            } catch (Exception exception) {
                ExplorerFix.LOGGER.error("Failed to load the offline word table", exception);
            }
            loaded = true;
        }
    }
}
