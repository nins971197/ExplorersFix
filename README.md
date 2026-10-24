# Explorer's Fix

Client-side Forge 1.20.1 companion for Explorer's Compass.

The jar is dual-sided: client mixins provide names, search and GUI caches;
common mixins cache server-side registry data and structure-search checks.
For dedicated-server benefits, install the same jar on the server as well.

It replaces the displayed structure name with:

```text
中文名 (English Name)
```

Example:

```text
甘草女巫塔 (Licowitch Tower)
```

Because the combined text is used by Explorer's Compass' existing search filter,
both Chinese and English names are searchable.

The runtime is fully offline. It does not call translation APIs, LLMs, or any
other network service. It scans the locally installed mods and resource packs
to build an English/Chinese translation memory.

## Requirements

- Minecraft 1.20.1
- Forge 47.x
- Explorer's Compass 1.20.1-1.3.3 or newer

## Name sources

The resolver uses the following order:

1. `config/explorersfix/names.json` explicit override.
2. The structure's own `structure.<namespace>.<path>` translation key.
3. The local translation memory, scored against map items, advancements, entities and biomes.
4. Same-namespace term and proper-noun memory learned from local language files.
5. The bundled offline word table and Chinese word-order rules.
6. If no Chinese evidence exists, the English name is kept unchanged.

There is intentionally no transliteration fallback. A name is generated only
when every meaningful token has a known Chinese term. Unknown proper nouns keep
their English name instead of receiving an invented Chinese string.

Proper nouns learned from entity, item, block, map or advancement names are
stored per namespace. A proper noun learned from one mod is never reused by a
different mod. Common structural terms remain in the shared offline dictionary.

The search filter accepts the Chinese name, the English name and all collected
aliases, including official map/advancement names. This is done without changing
the displayed combined name.

## Performance caches

Client:

- names are cached per structure ID and language generation;
- source names and dimension labels are cached;
- search aliases are lowercased once;
- search input is debounced before rebuilding the list;
- language/config reloads clear the client name caches.

Server/common:

- structure-to-structure-set mapping is cached per server level;
- allowed structure/blacklist results are cached;
- dimension and XP cost maps are cached;
- structure-presence checks are cached per search worker with a bounded 4096-entry cache;
- registry changes invalidate level caches.

The offline language index remains client-only; a dedicated server never scans
language files or resource packs.

`config/explorersfix/names.json` is created on first launch and can be edited
without rebuilding the mod. It supports both literal names and aliases to a
mod's own translation key:

```json
{
  "alexscaves:licowitch_tower": {
    "key": "entity.alexscaves.licowitch",
    "suffix": "塔",
    "en": "Licowitch Tower"
  },
  "some_mod:old_ruin": {
    "zh": "古老遗迹",
    "en": "Old Ruin"
  }
}
```

## Client config

The Forge client config is `config/explorersfix-client.toml`:

```toml
[general]
enabled = true
translationEnabled = true
showEnglish = true
autoGenerateNames = true
minimumConfidence = 40
logMissing = false
```

`translationEnabled = false` disables Chinese name resolution, offline language
indexing and Chinese alias search. The GUI/search/display caches and server-side
performance fixes remain active.

`autoGenerateNames = false` disables the offline generator for debugging.
`minimumConfidence` controls when generated evidence is accepted. A higher
value is stricter; 40 permits fully known dictionary compositions, while 60 or
above effectively requires map/advancement/official evidence.
`logMissing = true` writes structure IDs without a Chinese name to the game log.

## Offline name generation

On the first launch, the mod scans local language files in `mods` and
`resourcepacks`, plus structure data in global `kubejs/data`, world
`saves/*/datapacks`, and world `kubejs/data`. It builds an English/Chinese
translation memory plus a compact structure-semantic index, then stores it at:

```text
config/explorersfix/index-cache.json
```

Subsequent launches read this cache. The cache is stored per archive, so adding
or updating one mod only scans that archive; unchanged archives are reused.

This includes the language files downloaded by I18nUpdateMod. Both standard
keys and alternate community layouts are mapped back to structure IDs:

```text
structure.<ns>.<path>
filled_map.<ns>.<path>
filled_map.<ns>:<path>
worldgen.structure.<ns>.<path>
item.<ns>.<path>_map
advancements.<ns>.<path>.title
advancements.<ns>.discover_<path>.title
advancements.<ns>.find_<path>.title
```

The scan also reads Traditional Chinese (`zh_tw`/`zh_hk`) files and converts
common structure terms to Simplified Chinese with a bundled offline mapping.

The bundled `assets/explorersfix/words_zh_cn.json` contains common structure
terms, biome names, fantasy words and known proper nouns. The resolver combines
multi-word phrases and handles forms such as `village_<biome>` and
`pillager_outpost_<biome>`.

The generator is intentionally best-effort. It prefers official text from the
mod and the local translation memory when available. If an unknown proper noun
would require transliteration, it keeps the English name instead.

## Build

```powershell
.\gradlew.bat build --offline
```

The output jar is written to `build/libs/`.
