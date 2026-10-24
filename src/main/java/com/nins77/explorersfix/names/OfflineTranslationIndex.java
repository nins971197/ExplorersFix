package com.nins77.explorersfix.names;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nins77.explorersfix.ExplorerFix;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds a fully offline translation memory from installed mod and resource
 * pack language files. The scan is cached by a fingerprint of the local files.
 */
public final class OfflineTranslationIndex {
    private static final Gson GSON = new Gson();
    private static final String CACHE_VERSION = "6";
    private static final Pattern LANG_PATH =
            Pattern.compile("^assets/([^/]+)/lang/(en_us|zh_cn|zh_tw|zh_hk)\\.json$");
    private static final Pattern STRUCTURE_PATH =
            Pattern.compile("^data/([^/]+)/worldgen/structure/(.+)\\.json$");
    private static final Pattern NON_NAME = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final ExecutorService SCAN_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)),
            runnable -> {
                Thread thread = new Thread(runnable, "ExplorerFix-IndexScan");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }
    );

    private static volatile boolean ready;
    private static volatile boolean failed;
    private static volatile long retryAfter;
    private static volatile long generation;
    private static volatile Map<String, String> keysZh = Map.of();
    private static volatile Map<String, String> keysEn = Map.of();
    private static volatile Map<String, StructureHints> structures = Map.of();
    private static volatile Map<String, List<String>> structureKeys = Map.of();
    private static volatile Map<String, Map<String, String>> namespacePhrases = Map.of();
    private static volatile Map<String, Map<String, String>> namespaceTerms = Map.of();

    private OfflineTranslationIndex() {
    }

    public static void startLoading() {
        long now = System.currentTimeMillis();
        if (ready && !failed) {
            return;
        }
        if (failed && now < retryAfter) {
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(OfflineTranslationIndex::load, "ExplorerFix-NameIndex");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public static boolean isReady() {
        return ready;
    }

    public static long generation() {
        return generation;
    }

    public static String getChinese(String key) {
        return ready ? keysZh.get(key) : null;
    }

    public static String getEnglish(String key) {
        return ready ? keysEn.get(key) : null;
    }

    public static String getPhrase(String namespace, String englishName) {
        if (!ready || namespace == null || englishName == null) {
            return null;
        }
        String normalized = normalizePhrase(englishName);
        Map<String, String> scoped = namespacePhrases.get(namespace);
        if (scoped != null) {
            String value = scoped.get(normalized);
            if (value != null) {
                return value;
            }
            if (normalized.startsWith("the ")) {
                value = scoped.get(normalized.substring(4));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    public static String getTerm(String namespace, String token) {
        if (!ready || namespace == null || token == null) {
            return null;
        }
        Map<String, String> scoped = namespaceTerms.get(namespace);
        if (scoped == null) {
            return null;
        }
        String normalized = normalizePhrase(token).replace(' ', '_');
        return scoped.get(normalized);
    }

    public static StructureHints getHints(String structureId) {
        return ready ? structures.get(structureId) : null;
    }

    public static List<String> getKeysForStructure(String namespace, String path) {
        if (!ready) {
            return List.of();
        }
        return structureKeys.getOrDefault(namespace + ":" + path, List.of());
    }

    private static void activate(IndexData data) {
        keysZh = Map.copyOf(data.keysZh);
        keysEn = Map.copyOf(data.keysEn);
        structures = Map.copyOf(data.structures);
        structureKeys = buildStructureKeyIndex(data.keysZh, data.keysEn);
        namespacePhrases = copyNested(data.namespacePhrases);
        namespaceTerms = copyNested(data.namespaceTerms);
        generation++;
        ready = true;
    }

    private static void load() {
        long start = System.nanoTime();
        BuildResult result = null;
        Throwable failure = null;
        try {
            Path mods = FMLPaths.MODSDIR.get();
            Path resourcePacks = FMLPaths.GAMEDIR.get().resolve("resourcepacks");
            Path cache = FMLPaths.CONFIGDIR.get().resolve("explorersfix").resolve("index-cache.json");
            result = loadIncremental(mods, resourcePacks, cache);
            activate(result.data());
            failed = false;
            retryAfter = 0L;
        } catch (Throwable throwable) {
            failure = throwable;
            activateEmptyIndex();
            failed = true;
            retryAfter = System.currentTimeMillis() + 30_000L;
        } finally {
            STARTED.set(false);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        try {
            if (failure == null) {
                ExplorerFix.LOGGER.info(
                        "Offline translation index {} in {} ms: {} archives, {} Chinese keys, {} English keys, {} structures, {} aliases, {} namespaces",
                        result.fromCache() ? "loaded incrementally from cache" : "rebuilt with changed archives",
                        elapsedMs,
                        result.archiveCount(),
                        keysZh.size(),
                        keysEn.size(),
                        structures.size(),
                        structureKeys.size(),
                        namespacePhrases.size()
                );
            } else {
                ExplorerFix.LOGGER.error("Offline translation index failed; using the empty fallback index", failure);
            }
        } catch (Throwable ignored) {
            // Logging must not prevent the fallback index from becoming ready.
        }
    }

    private static void activateEmptyIndex() {
        try {
            activate(new IndexData());
        } catch (Throwable ignored) {
            keysZh = Map.of();
            keysEn = Map.of();
            structures = Map.of();
            structureKeys = Map.of();
            namespacePhrases = Map.of();
            namespaceTerms = Map.of();
            generation++;
            ready = true;
        }
    }

    private static BuildResult loadIncremental(Path mods, Path resourcePacks, Path cache) {
        Map<String, IndexData> cached = readArchiveCache(cache);
        List<ArchiveSpec> specs = collectArchives(mods, resourcePacks);
        Path shardDirectory = shardDirectory(cache);
        IndexData total = new IndexData();
        boolean changed = cached.isEmpty();
        int batchSize = 8;
        for (int start = 0; start < specs.size(); start += batchSize) {
            List<ArchiveSpec> batch = specs.subList(start, Math.min(specs.size(), start + batchSize));
            List<ArchiveSpec> missing = new ArrayList<>();
            for (ArchiveSpec spec : batch) {
                if (!cached.containsKey(spec.key())) {
                    missing.add(spec);
                    changed = true;
                }
            }

            Map<String, ScanResult> scanned = new HashMap<>();
            if (missing.size() > 1) {
                List<Future<ScanResult>> futures = new ArrayList<>();
                for (ArchiveSpec spec : missing) {
                    futures.add(SCAN_EXECUTOR.submit(() -> scanSpecSafe(spec)));
                }
                try {
                    for (Future<ScanResult> future : futures) {
                        ScanResult result = future.get();
                        scanned.put(result.key(), result);
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not scan archives", exception);
                }
            } else if (missing.size() == 1) {
                ScanResult result = scanSpecSafe(missing.get(0));
                scanned.put(result.key(), result);
            }

            for (ArchiveSpec spec : batch) {
                IndexData data = cached.get(spec.key());
                if (data == null) {
                    ScanResult result = scanned.get(spec.key());
                    data = result == null ? new IndexData() : result.data();
                    if (result != null && result.success()) {
                        try {
                            writeShard(shardDirectory, spec.key(), data);
                        } catch (Exception exception) {
                            ExplorerFix.LOGGER.warn("Could not write index shard for {}", spec.path(), exception);
                        }
                    }
                }
                mergeIndexData(total, data);
            }
        }
        return new BuildResult(total, !changed && !cached.isEmpty(), specs.size());
    }

    private static ScanResult scanSpecSafe(ArchiveSpec spec) {
        IndexData data = new IndexData();
        try {
            scanSpec(spec, data);
            return new ScanResult(spec.key(), data, true);
        } catch (Throwable throwable) {
            ExplorerFix.LOGGER.warn("Skipping archive while indexing: {}", spec.path(), throwable);
            return new ScanResult(spec.key(), data, false);
        }
    }

    private static List<ArchiveSpec> collectArchives(Path mods, Path resourcePacks) {
        List<ArchiveSpec> specs = new ArrayList<>();
        if (mods != null && Files.isDirectory(mods)) {
            try (Stream<Path> files = Files.list(mods)) {
                files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .forEach(path -> specs.add(new ArchiveSpec(
                                path, false, true, true, archiveKey(path))));
            } catch (Exception exception) {
                ExplorerFix.LOGGER.warn("Could not list mods for the incremental index", exception);
            }
        }
        if (resourcePacks != null && Files.isDirectory(resourcePacks)) {
            try (Stream<Path> files = Files.list(resourcePacks)) {
                files.sorted().forEach(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".zip")) {
                        specs.add(new ArchiveSpec(path, false, false, true, archiveKey(path)));
                    } else if (Files.isDirectory(path)) {
                        specs.add(new ArchiveSpec(path, true, false, true, archiveKey(path)));
                    }
                });
            } catch (Exception exception) {
                ExplorerFix.LOGGER.warn("Could not list resource packs for the incremental index", exception);
            }
        }
        collectDataSpecs(specs, mods);
        return specs;
    }

    private static void collectDataSpecs(List<ArchiveSpec> specs, Path mods) {
        Path gameDirectory = mods == null ? null : mods.getParent();
        if (gameDirectory == null) {
            return;
        }

        Path kubeJsData = gameDirectory.resolve("kubejs").resolve("data");
        if (Files.isDirectory(kubeJsData)) {
            specs.add(new ArchiveSpec(kubeJsData, true, true, true, archiveKey(kubeJsData)));
        }

        Path saves = gameDirectory.resolve("saves");
        if (!Files.isDirectory(saves)) {
            return;
        }
        try (Stream<Path> worlds = Files.list(saves)) {
            worlds.filter(Files::isDirectory).sorted().forEach(world -> {
                Path datapacks = world.resolve("datapacks");
                if (Files.isDirectory(datapacks)) {
                    try (Stream<Path> entries = Files.list(datapacks)) {
                        entries.sorted().forEach(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (name.endsWith(".zip")) {
                                specs.add(new ArchiveSpec(path, false, true, true, archiveKey(path)));
                            } else if (Files.isDirectory(path)) {
                                specs.add(new ArchiveSpec(path, true, true, true, archiveKey(path)));
                            }
                        });
                    } catch (Exception exception) {
                        ExplorerFix.LOGGER.warn("Could not list datapacks in {}", datapacks, exception);
                    }
                }

                Path worldKubeJs = world.resolve("kubejs").resolve("data");
                if (Files.isDirectory(worldKubeJs)) {
                    specs.add(new ArchiveSpec(worldKubeJs, true, true, true, archiveKey(worldKubeJs)));
                }
            });
        } catch (Exception exception) {
            ExplorerFix.LOGGER.warn("Could not list worlds for datapack indexing", exception);
        }
    }

    private static String archiveKey(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(path.toAbsolutePath().normalize().toString()
                    .getBytes(StandardCharsets.UTF_8));
            if (Files.isDirectory(path)) {
                List<Path> files = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile).sorted().forEach(files::add);
                }
                for (Path file : files) {
                    String relative = path.relativize(file).toString().replace('\\', '/');
                    if (!isRelevantIndexFile(relative)) {
                        continue;
                    }
                    String record = relative + "|" + Files.size(file)
                            + "|" + Files.getLastModifiedTime(file).toMillis() + "\n";
                    digest.update(record.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                String record = path.toAbsolutePath().normalize() + "|" + Files.size(path)
                        + "|" + Files.getLastModifiedTime(path).toMillis();
                digest.update(record.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static boolean isRelevantIndexFile(String relative) {
        return LANG_PATH.matcher(relative).matches()
                || STRUCTURE_PATH.matcher(relative).matches()
                || relative.matches("^[^/]+/worldgen/structure/.+\\.json$");
    }

    private static void mergeIndexData(IndexData target, IndexData source) {
        target.keysZh.putAll(source.keysZh);
        target.keysEn.putAll(source.keysEn);
        target.structures.putAll(source.structures);
        mergeNested(target.namespacePhrases, source.namespacePhrases);
        mergeNested(target.namespaceTerms, source.namespaceTerms);
    }

    private static void mergeNested(
            Map<String, Map<String, String>> target,
            Map<String, Map<String, String>> source
    ) {
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            target.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>())
                    .putAll(entry.getValue());
        }
    }

    private static void scanSpec(ArchiveSpec spec, IndexData data) {
        if (spec.directory()) {
            if (spec.languageData()) {
                scanResourceDirectory(spec.path(), data);
            }
            if (spec.structureData()) {
                scanDataDirectory(spec.path(), data);
            }
        } else {
            scanArchive(spec.path(), data, spec.structureData(), spec.languageData());
        }
    }

    private static void scanArchive(
            Path archive,
            IndexData data,
            boolean structureData,
            boolean languageData
    ) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Map<String, Map<String, String>> english = new HashMap<>();
            Map<String, Map<String, String>> chinese = new HashMap<>();
            Map<String, Map<String, String>> traditional = new HashMap<>();

            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                Matcher lang = LANG_PATH.matcher(name);
                if (languageData && lang.matches()) {
                    Map<String, String> values = readLanguage(zip.getInputStream(entry));
                    if ("en_us".equals(lang.group(2))) {
                        english.put(lang.group(1), values);
                    } else if ("zh_cn".equals(lang.group(2))) {
                        chinese.put(lang.group(1), values);
                    } else {
                        traditional.put(lang.group(1), values);
                    }
                    continue;
                }

                if (structureData) {
                    Matcher structure = STRUCTURE_PATH.matcher(name);
                    if (structure.matches()) {
                        StructureHints hints = readHints(zip.getInputStream(entry));
                        if (hints != null) {
                            data.structures.put(structure.group(1) + ":" + structure.group(2), hints);
                        }
                    }
                }
            }

            Set<String> namespaces = new HashSet<>(english.keySet());
            namespaces.addAll(chinese.keySet());
            namespaces.addAll(traditional.keySet());
            for (String namespace : namespaces) {
                Map<String, String> mergedChinese = new HashMap<>();
                for (Map.Entry<String, String> entry
                        : traditional.getOrDefault(namespace, Map.of()).entrySet()) {
                    mergedChinese.put(entry.getKey(), TraditionalChinese.convert(entry.getValue()));
                }
                mergedChinese.putAll(chinese.getOrDefault(namespace, Map.of()));
                mergeLanguage(data, namespace,
                        english.getOrDefault(namespace, Map.of()),
                        mergedChinese);
            }
        } catch (Exception exception) {
            ExplorerFix.LOGGER.debug("Skipping unreadable archive {}", archive, exception);
        }
    }

    private static void scanResourceDirectory(Path root, IndexData data) {
        Map<String, Map<String, String>> english = new HashMap<>();
        Map<String, Map<String, String>> chinese = new HashMap<>();
        Map<String, Map<String, String>> traditional = new HashMap<>();

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                Matcher lang = LANG_PATH.matcher(relative);
                if (!lang.matches()) {
                    return;
                }
                try (InputStream stream = Files.newInputStream(path)) {
                    Map<String, String> values = readLanguage(stream);
                    if ("en_us".equals(lang.group(2))) {
                        english.put(lang.group(1), values);
                    } else if ("zh_cn".equals(lang.group(2))) {
                        chinese.put(lang.group(1), values);
                    } else {
                        traditional.put(lang.group(1), values);
                    }
                } catch (Exception exception) {
                    ExplorerFix.LOGGER.debug("Skipping unreadable language file {}", path, exception);
                }
            });
        } catch (Exception exception) {
            ExplorerFix.LOGGER.warn("Could not scan resource pack directory {}", root, exception);
        }

        Set<String> namespaces = new HashSet<>(english.keySet());
        namespaces.addAll(chinese.keySet());
        namespaces.addAll(traditional.keySet());
        for (String namespace : namespaces) {
            Map<String, String> mergedChinese = new HashMap<>();
            for (Map.Entry<String, String> entry
                    : traditional.getOrDefault(namespace, Map.of()).entrySet()) {
                mergedChinese.put(entry.getKey(), TraditionalChinese.convert(entry.getValue()));
            }
            mergedChinese.putAll(chinese.getOrDefault(namespace, Map.of()));
            mergeLanguage(data, namespace,
                    english.getOrDefault(namespace, Map.of()),
                    mergedChinese);
        }
    }

    private static void scanDataDirectory(Path root, IndexData data) {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                Matcher structure = STRUCTURE_PATH.matcher(relative);
                if (!structure.matches()) {
                    // KubeJS data roots start at <namespace>/ instead of data/<namespace>/.
                    structure = STRUCTURE_PATH.matcher("data/" + relative);
                }
                if (!structure.matches()) {
                    return;
                }
                try (InputStream stream = Files.newInputStream(path)) {
                    StructureHints hints = readHints(stream);
                    if (hints != null) {
                        data.structures.put(structure.group(1) + ":" + structure.group(2), hints);
                    }
                } catch (Exception exception) {
                    ExplorerFix.LOGGER.debug("Skipping unreadable structure file {}", path, exception);
                }
            });
        } catch (Exception exception) {
            ExplorerFix.LOGGER.warn("Could not scan data directory {}", root, exception);
        }
    }

    private static Map<String, String> readLanguage(InputStream stream) {
        Map<String, String> result = new HashMap<>();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return result;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    result.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception ignored) {
            // Broken language entries are ignored; the original mod may still
            // provide a usable value in another resource pack.
        }
        return result;
    }

    private static StructureHints readHints(InputStream stream) {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            String text = new java.io.BufferedReader(reader).lines()
                    .filter(line -> !line.trim().startsWith("//"))
                    .collect(Collectors.joining("\n"));
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            String type = getString(object, "type");
            String startPool = getString(object, "start_pool");
            JsonElement biomeElement = object.get("biomes");
            String biomes = biomeElement == null ? null : biomeElement.toString();
            return new StructureHints(type, startPool, biomes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String getString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsString();
    }

    private static void mergeLanguage(
            IndexData data,
            String namespace,
            Map<String, String> english,
            Map<String, String> chinese
    ) {
        if (english.isEmpty() && chinese.isEmpty()) {
            return;
        }

        Set<String> keys = new HashSet<>(english.keySet());
        keys.addAll(chinese.keySet());
        for (String key : keys) {
            String en = english.get(key);
            String zh = chinese.get(key);

            if (isRelevantKey(key)) {
                if (zh != null && !zh.isBlank()) {
                    data.keysZh.put(key, zh.trim());
                }
                if (en != null && !en.isBlank()) {
                    data.keysEn.put(key, en.trim());
                }
            }

            if (shouldIndexPhrase(key, en, zh)) {
                String normalized = normalizePhrase(en);
                data.namespacePhrases
                        .computeIfAbsent(namespace, ignored -> new HashMap<>())
                        .put(normalized, zh.trim());
            }

            if (isTermSourceKey(key)) {
                learnTerms(data, namespace, key, en, zh);
            }
        }
    }

    private static boolean isTermSourceKey(String key) {
        return key.startsWith("structure.")
                || key.startsWith("worldgen.structure.")
                || key.startsWith("filled_map.")
                || key.startsWith("entity.")
                || key.startsWith("biome.")
                || key.startsWith("item.")
                || key.startsWith("block.")
                || key.startsWith("advancements.");
    }

    private static void learnTerms(
            IndexData data,
            String namespace,
            String key,
            String en,
            String zh
    ) {
        if (en == null || zh == null || en.isBlank() || zh.isBlank()
                || en.contains("%") || zh.contains("%") || zh.length() > 32) {
            return;
        }
        String chinese = cleanTermChinese(zh, key);
        if (chinese.isBlank()) {
            return;
        }

        String english = cleanTermEnglish(en, key);
        if (english.isBlank()) {
            return;
        }
        String normalizedEnglish = normalizePhrase(english);
        if (normalizedEnglish.isBlank()) {
            return;
        }

        String keyTail = keyTail(key);
        if (keyTail.isBlank()) {
            return;
        }

        int words = wordCount(normalizedEnglish);
        if (words == 1) {
            String token = normalizedEnglish;
            if (keyTail.contains(token)) {
                data.namespaceTerms
                        .computeIfAbsent(namespace, ignored -> new HashMap<>())
                        .putIfAbsent(token, chinese);
            }
            return;
        }

        String normalizedKey = normalizePhrase(keyTail);
        if (normalizedKey.contains(normalizedEnglish)) {
            data.namespaceTerms
                    .computeIfAbsent(namespace, ignored -> new HashMap<>())
                    .putIfAbsent(normalizedEnglish.replace(' ', '_'), chinese);
        }
    }

    private static String cleanTermChinese(String value, String key) {
        String cleaned = value.trim();
        if (key.startsWith("filled_map.") || (key.startsWith("item.") && key.endsWith("_map"))) {
            for (String suffix : new String[]{
                    "探险家地图", "探索地图", "寻路地图", "地图", " Explorer Map", " Explorer's Map", " Map"
            }) {
                if (cleaned.endsWith(suffix)) {
                    cleaned = cleaned.substring(0, cleaned.length() - suffix.length()).trim();
                }
            }
        }
        for (String suffix : new String[]{"刷怪蛋", " Spawn Egg"}) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.substring(0, cleaned.length() - suffix.length()).trim();
            }
        }
        return cleaned;
    }

    private static String cleanTermEnglish(String value, String key) {
        String cleaned = value.trim();
        for (String article : new String[]{"The ", "A ", "An "}) {
            if (cleaned.regionMatches(true, 0, article, 0, article.length())) {
                cleaned = cleaned.substring(article.length());
                break;
            }
        }
        for (String suffix : new String[]{
                " Explorer Map", " Explorer's Map", " Map", " Spawn Egg"
        }) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.substring(0, cleaned.length() - suffix.length());
            }
        }
        if (key.startsWith("item.") && key.endsWith("_map") && cleaned.equalsIgnoreCase("Map")) {
            return "";
        }
        return cleaned.trim();
    }

    private static String keyTail(String key) {
        String rest;
        if (key.startsWith("worldgen.structure.")) {
            rest = key.substring("worldgen.structure.".length());
        } else {
            int firstDot = key.indexOf('.');
            if (firstDot < 0) {
                return "";
            }
            rest = key.substring(firstDot + 1);
        }

        if (key.startsWith("advancements.")) {
            if (rest.endsWith(".title")) {
                rest = rest.substring(0, rest.length() - ".title".length());
            } else {
                return "";
            }
        }
        if (key.startsWith("item.") && rest.endsWith("_map")) {
            rest = rest.substring(0, rest.length() - "_map".length());
        }
        if (rest.startsWith("discover_")) {
            rest = rest.substring("discover_".length());
        }
        if (rest.startsWith("find_")) {
            rest = rest.substring("find_".length());
        }

        int separator = rest.indexOf(':');
        if (separator >= 0) {
            rest = rest.substring(separator + 1);
        } else {
            int dot = rest.indexOf('.');
            if (dot >= 0) {
                rest = rest.substring(dot + 1);
            }
        }
        return rest;
    }

    private static boolean isRelevantKey(String key) {
        return key.startsWith("structure.")
                || key.startsWith("worldgen.structure.")
                || key.startsWith("filled_map.")
                || key.startsWith("advancements.")
                || key.startsWith("entity.")
                || key.startsWith("biome.")
                || (key.startsWith("item.") && key.endsWith("_map"));
    }

    private static boolean shouldIndexPhrase(String key, String en, String zh) {
        if (en == null || zh == null || en.isBlank() || zh.isBlank()
                || en.equals(zh) || en.indexOf('%') >= 0 || zh.indexOf('%') >= 0
                || zh.length() > 48) {
            return false;
        }
        if (key.endsWith(".desc") || key.endsWith(".description")
                || key.endsWith(".info") || key.endsWith(".tooltip")
                || key.contains(".lore.")) {
            return false;
        }
        String prefix = key.substring(0, Math.min(key.length(), key.indexOf('.') + 1));
        if (!(prefix.equals("structure.") || prefix.equals("filled_map.")
                || prefix.equals("advancements.") || prefix.equals("entity.")
                || prefix.equals("biome.") || prefix.equals("item.")
                || prefix.equals("block.") || prefix.equals("book.")
                || prefix.equals("worldgen."))) {
            return false;
        }
        return wordCount(en) <= 8;
    }

    private static Map<String, List<String>> buildStructureKeyIndex(
            Map<String, String> chinese,
            Map<String, String> english
    ) {
        Map<String, Set<String>> mutable = new HashMap<>();
        Set<String> keys = new HashSet<>(chinese.keySet());
        keys.addAll(english.keySet());
        for (String key : keys) {
            String structureId = structureIdForKey(key);
            if (structureId != null) {
                mutable.computeIfAbsent(structureId, ignored -> new HashSet<>()).add(key);
            }
        }

        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : mutable.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static String structureIdForKey(String key) {
        String rest;
        if (key.startsWith("worldgen.structure.")) {
            rest = key.substring("worldgen.structure.".length());
        } else if (key.startsWith("structure.")
                || key.startsWith("filled_map.")
                || key.startsWith("entity.")
                || key.startsWith("biome.")
                || key.startsWith("advancements.")
                || key.startsWith("item.")) {
            rest = key.substring(key.indexOf('.') + 1);
        } else {
            return null;
        }

        if (key.startsWith("advancements.")) {
            if (rest.endsWith(".title")) {
                rest = rest.substring(0, rest.length() - ".title".length());
            } else if (rest.endsWith(".description") || rest.endsWith(".desc")) {
                return null;
            }
        }
        if (key.startsWith("item.")) {
            if (!rest.endsWith("_map")) {
                return null;
            }
            rest = rest.substring(0, rest.length() - "_map".length());
        }
        if (rest.endsWith(".tooltip")) {
            return null;
        }
        if (rest.startsWith("discover_")) {
            rest = rest.substring("discover_".length());
        }
        if (rest.startsWith("find_")) {
            rest = rest.substring("find_".length());
        }

        if (rest.contains(":")) {
            return rest;
        }
        int dot = rest.indexOf('.');
        if (dot > 0) {
            return rest.substring(0, dot) + ":" + rest.substring(dot + 1);
        }
        return null;
    }

    private static int wordCount(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    private static String normalizePhrase(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('\'', ' ');
        lower = NON_NAME.matcher(lower).replaceAll(" ");
        return SPACES.matcher(lower).replaceAll(" ").trim();
    }

    private static Path shardDirectory(Path cache) {
        String name = cache.getFileName().toString();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        return cache.resolveSibling(name + "-shards");
    }

    private static Map<String, IndexData> readArchiveCache(Path cache) {
        Map<String, IndexData> result = new HashMap<>();
        if (Files.isRegularFile(cache)) {
            try (Reader reader = Files.newBufferedReader(cache, StandardCharsets.UTF_8)) {
                JsonElement rootElement = JsonParser.parseReader(reader);
                if (rootElement.isJsonObject()) {
                    JsonObject root = rootElement.getAsJsonObject();
                    JsonObject archives = root.getAsJsonObject("archives");
                    if (CACHE_VERSION.equals(getString(root, "version")) && archives != null) {
                        for (Map.Entry<String, JsonElement> entry : archives.entrySet()) {
                            if (entry.getValue().isJsonObject()) {
                                result.put(entry.getKey(), readArchiveData(entry.getValue().getAsJsonObject()));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        Path shards = shardDirectory(cache);
        if (Files.isDirectory(shards)) {
            try (Stream<Path> files = Files.list(shards)) {
                files.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(path -> {
                            String name = path.getFileName().toString();
                            IndexData data = readShard(path);
                            if (data != null) {
                                result.put(name.substring(0, name.length() - ".json".length()), data);
                            }
                        });
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static IndexData readShard(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) {
                return null;
            }
            JsonObject root = rootElement.getAsJsonObject();
            if (!CACHE_VERSION.equals(getString(root, "version"))
                    || !root.has("data")
                    || !root.get("data").isJsonObject()) {
                return null;
            }
            return readArchiveData(root.getAsJsonObject("data"));
        } catch (Exception exception) {
            return null;
        }
    }

    private static void writeShard(Path directory, String key, IndexData data) throws Exception {
        Files.createDirectories(directory);
        JsonObject root = new JsonObject();
        root.addProperty("version", CACHE_VERSION);
        root.add("data", archiveDataToJson(data));
        writeJsonAtomic(directory.resolve(key + ".json"), root);
    }

    private static IndexData readArchiveData(JsonObject object) {
        IndexData data = new IndexData();
        readMap(object.getAsJsonObject("keysZh"), data.keysZh);
        readMap(object.getAsJsonObject("keysEn"), data.keysEn);
        readNestedMap(object.getAsJsonObject("namespacePhrases"), data.namespacePhrases);
        readNestedMap(object.getAsJsonObject("namespaceTerms"), data.namespaceTerms);
        JsonObject structuresObject = object.getAsJsonObject("structures");
        if (structuresObject != null) {
            for (Map.Entry<String, JsonElement> entry : structuresObject.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject hint = entry.getValue().getAsJsonObject();
                data.structures.put(entry.getKey(), new StructureHints(
                        getString(hint, "type"),
                        getString(hint, "startPool"),
                        getString(hint, "biomes")
                ));
            }
        }
        return data;
    }

    private static JsonObject archiveDataToJson(IndexData data) {
        JsonObject object = new JsonObject();
        object.add("keysZh", GSON.toJsonTree(data.keysZh));
        object.add("keysEn", GSON.toJsonTree(data.keysEn));
        object.add("namespacePhrases", GSON.toJsonTree(data.namespacePhrases));
        object.add("namespaceTerms", GSON.toJsonTree(data.namespaceTerms));
        JsonObject structuresObject = new JsonObject();
        for (Map.Entry<String, StructureHints> entry : data.structures.entrySet()) {
            JsonObject hint = new JsonObject();
            if (entry.getValue().type() != null) {
                hint.addProperty("type", entry.getValue().type());
            }
            if (entry.getValue().startPool() != null) {
                hint.addProperty("startPool", entry.getValue().startPool());
            }
            if (entry.getValue().biomes() != null) {
                hint.addProperty("biomes", entry.getValue().biomes());
            }
            structuresObject.add(entry.getKey(), hint);
        }
        object.add("structures", structuresObject);
        return object;
    }

    private static void writeJsonAtomic(Path cache, JsonObject root) throws Exception {
        Path temporary = cache.resolveSibling(cache.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void readMap(JsonObject object, Map<String, String> target) {
        if (object == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                target.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    private static void readNestedMap(
            JsonObject object,
            Map<String, Map<String, String>> target
    ) {
        if (object == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            Map<String, String> values = new HashMap<>();
            readMap(entry.getValue().getAsJsonObject(), values);
            target.put(entry.getKey(), values);
        }
    }

    private static Map<String, Map<String, String>> copyNested(
            Map<String, Map<String, String>> source
    ) {
        Map<String, Map<String, String>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            result.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    public record StructureHints(String type, String startPool, String biomes) {
    }

    private record BuildResult(IndexData data, boolean fromCache, int archiveCount) {
    }

    private record ArchiveSpec(
            Path path,
            boolean directory,
            boolean structureData,
            boolean languageData,
            String key
    ) {
    }

    private record ScanResult(String key, IndexData data, boolean success) {
    }

    private static final class IndexData {
        private final Map<String, String> keysZh = new HashMap<>();
        private final Map<String, String> keysEn = new HashMap<>();
        private final Map<String, StructureHints> structures = new HashMap<>();
        private final Map<String, Map<String, String>> namespacePhrases = new HashMap<>();
        private final Map<String, Map<String, String>> namespaceTerms = new HashMap<>();
    }
}
