# LetMeLook

CoreProtect replay + timelapse ghost rendering for **MC 1.21.11**.

- Server: **Purpur 1.21.11 (build 2568)** + CoreProtect CE 24.x → `letmelook-purpur`
- Client: **Fabric Loader 0.19.5 / Yarn 1.21.11+build.6 / Fabric API 0.141.6+1.21.11 / Java 21** → `letmelook-client`
- Shared wire protocol → `letmelook-common` (`letmelook:lookup` channel)

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export https_proxy=http://127.0.0.1:2080 http_proxy=http://127.0.0.1:2080
./gradlew build
```

Outputs:

- `letmelook-purpur/build/libs/letmelook-purpur-0.1.0.jar` → drop into Purpur `plugins/` next to CoreProtect
- `letmelook-client/build/libs/letmelook-client-0.1.0.jar` → Fabric client mods folder

## W1 scope

- [x] Protocol structs (`LookupRequest` / `TimelineChunk` / `BlockEvent` / gzip codec)
- [x] Purpur bridge: permission check → rate limit → async CO `blockLookup` → paged `sendPluginMessage`
- [x] Fabric client: `/lm pos1|pos2|status`, timeline page ingest into `GhostLayer`
- [ ] W2: cuboid bulk scan (`performLookup`), ghost translucent renderer, time slider, camera path + ffmpeg export

## Server setup

1. Purpur 1.21.11 + CoreProtect CE 24.1 installed and logging.
2. Copy `letmelook-purpur` jar to `plugins/`, restart.
3. Grant `letmelook.use` (default: op).
4. Client connects with `letmelook-client` installed, selects region, requests replay.
