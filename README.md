# LetMeLook

CoreProtect replay + timelapse ghost rendering for **MC 1.21.11**.

- Server: **Purpur 1.21.11 (build 2568)** + CoreProtect CE 24.x → `letmelook-purpur`
- Client: **Fabric Loader 0.19.5 / Yarn 1.21.11+build.6 / Fabric API 0.141.6+1.21.11 / Java 21** → `letmelook-client`
- Shared wire protocol → `letmelook-common` (`letmelook:lookup` channel)

> Research note: this repo is developed as a research project. W1 proved the
> protocol path end-to-end; W2 (this tree) completes the feature set below.
> Direct network access is required for the first Gradle build (loom +
> mappings download); afterwards `--offline` works from cache.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew build
```

(`gradle-wrapper.properties` pins 9.5.0; any local Gradle 9.5+ with the same
user home works, e.g. `~/.gradle/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle`.)

Outputs:

- `letmelook-purpur/build/libs/letmelook-purpur.jar` → drop into Purpur `plugins/` next to CoreProtect
- `letmelook-client/build/libs/letmelook-client-0.1.0.jar` → Fabric client mods folder

## Scope

- [x] W1: Protocol structs (`LookupRequest` / `TimelineChunk` / `BlockEvent` / gzip codec)
- [x] W1: Purpur bridge: permission check → rate limit → async CO `blockLookup` → paged `sendPluginMessage`
- [x] W1: Fabric client: `/lm pos1|pos2|status`, timeline page ingest into `GhostLayer`
- [x] W2: common — adaptive gzip framing (plain JSON < 32 KiB stays W1-compatible, larger pages gzipped; magic-header auto-detect)
- [x] W2: server — single `performLookup` cuboid bulk scan (sphere envelope + Java-side cuboid clip), correct `from`/`to` per break/place, byte-budget paging (`limits.page-bytes`, default 64 KiB), volume/cooldown guards kept
- [x] W2: client — `/lm lookup <seconds> [user]` C2S send, `ReplayTimeline` (sorted ingest, playhead, play/pause/speed, replay/rollback/diff modes)
- [x] W2: client — translucent ghost renderer (`debugFilledBox` fill + `lines` outline, place=green / break=red, camera-relative verts, 4096 boxes/frame cap)
- [x] W2: client — `/lm time` slider screen, `/lm play|pause|speed|mode|hide|show|clear`
- [x] W2: client — camera path (`/lm cam add|clear|play|stop`, detached rig entity, yaw/pitch lerp)
- [x] W2: client — timelapse export (`/lm export <fps> <seconds>`: HUD-hidden screenshot burst + `make-timelapse.sh` ffmpeg script)

## Server setup

1. Purpur 1.21.11 + CoreProtect CE 24.1 installed and logging.
2. Copy `letmelook-purpur` jar to `plugins/`, restart.
3. Grant `letmelook.use` (default: op). Tune `plugins/LetMeLook/config.yml` (`limits.max-volume`, `limits.cooldown-ms`, `limits.page-bytes`).
4. Client connects with `letmelook-client` installed, selects region (`/lm pos1`, `/lm pos2`), requests replay (`/lm lookup 3600 [user]`).

## Client usage

```
/lm pos1 / pos2          select cuboid corners
/lm lookup 3600 Steve    replay last hour for Steve (omit user = everyone)
/lm status               selection + ingest progress
/lm time                 open time slider (scrub / play / speed / mode)
/lm mode replay|rollback|diff
/lm cam add (x2+) → /lm cam play
/lm export 30 20         30fps x 20s burst → run make-timelapse.sh with ffmpeg
```

Exported frames land in `<run>/letmelook-timelapse-<timestamp>/` as `frame-*.png`.
