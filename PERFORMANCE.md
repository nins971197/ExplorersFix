# Performance check

Measured on Windows with JDK 17 and the local mod/resource-pack caches. These
numbers are for the offline index only; no network is used.

## Test instance

Path: `1.20.1-Forge_47.4.23`

```text
scan:          641 ms
Chinese keys:  12,890
English keys:  988
phrases:       3,270
structures:    42
```

## Large instance

Path: `--`

```text
scan (warm):   684 ms
scan (cold):   1,325 ms
cache write:   142 ms
cache read:    64 ms
cache size:    1.87 MB
Chinese keys:  14,487
English keys:  3,202
phrases:       6,908
structures:    250
```

## Incremental archive cache

Large instance:

```text
first build:   353 ms
cached load:   53 ms
archives:      303
cache size:    1.99 MB
namespaces with learned terms: 73
```

## Cached name generation

```text
10 ms / 100,000 lookups
≈ 0.10 µs per cached lookup
```

## Runtime behavior

- The index scan runs on a daemon thread, so opening the compass does not wait
  for the first scan.
- The index is cached at `config/explorersfix/index-cache.json`.
- A fingerprint of local mod jars and resource packs invalidates the cache when
  files change.
- Resolved names are cached per structure ID and invalidated when the index or
  `names.json` changes.
- The index stores only relevant name keys and short aligned phrases, not every
  language entry from every mod.

