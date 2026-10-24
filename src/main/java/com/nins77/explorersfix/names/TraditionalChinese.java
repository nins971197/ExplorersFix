package com.nins77.explorersfix.names;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nins77.explorersfix.ExplorerFix;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Compact offline Traditional-to-Simplified conversion for structure names. */
final class TraditionalChinese {
    private static final Map<String, String> PHRASES = new HashMap<>();
    private static final Map<Character, Character> CHARACTERS = new HashMap<>();
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile List<Map.Entry<String, String>> sortedPhrases = List.of();
    private static volatile boolean loaded;

    private TraditionalChinese() {
    }

    static String convert(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String cached = CACHE.get(value);
        if (cached != null) {
            return cached;
        }

        ensureLoaded();
        StringBuilder result = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            String matchedPhrase = null;
            for (Map.Entry<String, String> entry : sortedPhrases) {
                if (value.startsWith(entry.getKey(), index)) {
                    matchedPhrase = entry.getValue();
                    index += entry.getKey().length();
                    break;
                }
            }
            if (matchedPhrase != null) {
                result.append(matchedPhrase);
                continue;
            }

            char character = value.charAt(index++);
            Character simplified = CHARACTERS.get(character);
            result.append(simplified == null ? character : simplified);
        }

        String converted = result.toString();
        CACHE.put(value, converted);
        return converted;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (TraditionalChinese.class) {
            if (loaded) {
                return;
            }
            try (InputStream stream = TraditionalChinese.class.getResourceAsStream(
                    "/assets/explorersfix/traditional_zh.json")) {
                if (stream != null) {
                    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                            if (entry.getKey().startsWith("_") || !entry.getValue().isJsonPrimitive()) {
                                continue;
                            }
                            String source = entry.getKey();
                            String target = entry.getValue().getAsString();
                            if (source.length() == 1 && target.length() == 1) {
                                CHARACTERS.put(source.charAt(0), target.charAt(0));
                            } else {
                                PHRASES.put(source, target);
                            }
                        }
                    }
                }
            } catch (Exception exception) {
                ExplorerFix.LOGGER.warn("Could not load the Traditional Chinese mapping", exception);
            }
            List<Map.Entry<String, String>> phrases = new ArrayList<>(PHRASES.entrySet());
            phrases.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                    .reversed());
            sortedPhrases = List.copyOf(phrases);
            loaded = true;
        }
    }
}
