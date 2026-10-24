# LetMeLook

CoreProtect replay + timelapse ghost rendering for **MC 1.21.11**.

- Server: **Purpur 1.21.11 (build 2568)** + CoreProtect CE 24.x → `letmelook-purpur`
- Client: **Fabric Loader 0.19.5 / Yarn 1.21.11+build.6 / Fabric API 0.141.6+1.21.11 / Java 21** → `letmelook-client`
- Shared wire protocol → `letmelook-common` (`letmelook:lookup` channel)

> Research project. W1 proved the protocol path end-to-end, W2 completed the
> first feature set, and **W3 (this tree) fixes two show-stoppers and adds
> status reporting, a selection renderer, real replay reconstruction and a HUD.**
> Direct network access is required for the first Gradle build (loom +
> mappings download); afterwards `--offline` works from cache.

## Build & test

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew build          # compiles all three modules and runs 40 unit tests
```

`gradle-wrapper.properties` pins 9.5.0, but any local Gradle 9.5+ works with the
same user home, e.g.
`~/.gradle/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle build`.

Outputs:

- `letmelook-purpur/build/libs/letmelook-purpur.jar` → drop into Purpur `plugins/` next to CoreProtect
- `letmelook-client/build/libs/letmelook-client-0.2.0.jar` → Fabric client mods folder

### What the test suite covers

| Module | Suite | Tests |
| --- | --- | --- |
| `letmelook-common` | `CodecTest` | adaptive gzip framing, single-inflate `unwrap` + `peekKind` routing, W1/W2 payloads with no `kind`, malformed input, page metadata |
| `letmelook-common` | `ActionFilterTest` | wire action names/ids, duplicate collapse, safe default |
| `letmelook-common` | `LookupStatusTest` | status defaults, reason bounding, terminality |
| `letmelook-purpur` | `RequestLimiterTest` | shape/volume validation (incl. reversed axes), cooldown, per-minute cap, TTL eviction |
| `letmelook-client` | `ReplayTimelineTest` | paged ingest, stale-page rejection, settling on a terminal status, DIFF windows, play/pause/speed, playhead clamping, render-cache revision |

## Scope

- [x] W1: Protocol structs (`LookupRequest` / `TimelineChunk` / `BlockEvent` / gzip codec)
- [x] W1: Purpur bridge: permission check → rate limit → async CO `blockLookup` → paged `sendPluginMessage`
- [x] W1: Fabric client: `/lm pos1|pos2|status`, timeline page ingest into `GhostLayer`
- [x] W2: common — adaptive gzip framing (plain JSON < 32 KiB stays W1-compatible, larger pages gzipped; magic-header auto-detect)
- [x] W2: server — single `performLookup` cuboid bulk scan (sphere envelope + Java-side cuboid clip), correct `from`/`to` per break/place, byte-budget paging, volume/cooldown guards
- [x] W2: client — `/lm lookup <seconds> [user]`, `ReplayTimeline`, translucent ghost renderer, time slider, camera path, timelapse export
- [x] W3: **fix** — the S2C payload was registered twice (`NetChannels` *and* `TimelineReceiver`), so Fabric threw `Payload type … is already registered` and the client mod could not start at all; the legacy receiver is gone and registration happens once
- [x] W3: **fix** — a page from a superseded request could clear the timeline that replaced it; stale pages are now dropped
- [x] W3: **fix** — `isComplete()` never became true for an empty or refused result; a terminal server status now settles the request
- [x] W3: protocol — `LookupStatus` acknowledgements multiplexed on the same channel via a `kind` discriminator (`timeline` / `status`), plus `pages`/`truncated` on every page; still decodes W2 payloads unchanged
- [x] W3: server — every request is answered (`ACCEPTED`/`DENIED`/`THROTTLED`/`ERROR`/`DONE`), so a rejected lookup no longer leaves the client hanging with no explanation
- [x] W3: server — pages are delivered a few per tick instead of all in one tick (a large scan used to be able to dump tens of MB into a single tick), `DONE` is sent only after the last page, and the event cap is reported honestly as `truncated`
- [x] W3: server — `/letmelook status|reload` admin command, per-minute request cap, idle limiter-state eviction (W2 leaked one entry per player UUID forever), per-axis region validation that rejects reversed coordinates
- [x] W3: client — selection wireframe renderer (W2 documented `/lm` selection as "rendered as highlight box" but nothing drew it), with per-corner markers and dimension tracking
- [x] W3: client — ghost rendering folds events into a last-writer-wins world reconstruction instead of stacking a box per edit; boxes are chosen nearest-first under the frame cap
- [x] W3: client — `ROLLBACK` now shows only the positions whose *live* block differs from the reconstructed state, i.e. exactly what a rollback to the playhead would change
- [x] W3: client — `DIFF` mode actually filters (`/lm window <seconds>`); it was an enum value with no behaviour
- [x] W3: client — HUD overlay (mode, playhead, ingest bar, selection, last status), rebindable keybinds, mod icon and language file
- [x] W3: `RequestLimiter` is Bukkit-free (UUID + injected clock) so the whole admission policy is unit-testable without a server

## Wire protocol

One channel, `letmelook:lookup`, carrying Gson JSON that is gzip-compressed
above 32 KiB (the receiver auto-detects the gzip magic, so W1 plain payloads
still work).

Every server→client payload has a top-level `kind`:

- `"timeline"` → `TimelineChunk` (paged events; the default when `kind` is
  absent, which is what a W2 plugin sends)
- `"status"` → `LookupStatus` (`ACCEPTED`, `DENIED`, `THROTTLED`, `ERROR`, `DONE`)

The client inflates a payload once and reads only the leading `kind` header
(`Codec.peekKind`) before deciding how to decode it.

## Server setup

1. Purpur 1.21.11 + CoreProtect CE 24.1 installed and logging.
2. Copy `letmelook-purpur` jar to `plugins/`, restart.
3. Grant `letmelook.use` (default: op). Tune `plugins/LetMeLook/config.yml`.
4. `/letmelook status` shows the CoreProtect hook, channel, uptime and active
   limits; `/letmelook reload` re-reads `config.yml` (permission
   `letmelook.admin`, alias `/lml`).

`config.yml` keys:

| Key | Default | Meaning |
| --- | --- | --- |
| `limits.max-volume` | `1048576` | max blocks per region; `<= 0` disables lookups |
| `limits.cooldown-ms` | `3000` | per-player cooldown between lookups |
| `limits.requests-per-minute` | `12` | per-player cap over any rolling minute (`0` = off) |
| `limits.page-bytes` | `65536` | target encoded bytes per page (halves the event count until it fits) |
| `limits.max-events` | `50000` | hard cap; the reply is flagged `truncated` when hit |
| `limits.max-pages` | `512` | hard cap on pages per request |
| `limits.pages-per-tick` | `4` | delivery pacing (each page is ≤ `page-bytes`) |
| `limits.state-ttl-ms` | `900000` | limiter state is dropped after this much idle time |

## Client usage

```
/lm pos1 / pos2          select cuboid corners (dimension-aware)
/lm lookup 3600 Steve    replay last hour for Steve (omit user = everyone)
/lm status               selection + timeline + last server status
/lm time                 open time slider (scrub / play / speed / mode / window)
/lm mode replay|rollback|diff
/lm window 60            DIFF-mode lookback window in seconds
/lm hud                  toggle the on-screen overlay
/lm cam add (x2+) → /lm cam play
/lm export 30 20         30fps x 20s burst → run make-timelapse.sh with ffmpeg
```

Keybinds (rebindable under Controls): **L** opens the timeline slider, **H**
toggles the overlay.

Exported frames land in `<run>/letmelook-timelapse-<timestamp>/` as `frame-*.png`.

## Verification status

Verified for this revision:

- `gradle build` is green: all three modules compile against the real 1.21.11
  Yarn mappings / Paper API, and **40/40 unit tests pass**.
- Jar contents were inspected: the client jar carries the remapped classes,
  `fabric.mod.json`, the mixin config, the language file and a valid 64×64 RGBA
  icon; the plugin jar carries `plugin.yml`, `config.yml` and the bundled
  `common` classes.
- Protocol compatibility (gzip framing, `kind` routing, W2 payloads with no
  `kind`) is covered by tests.

**Not verified** — this environment has no Minecraft client, display or live
CoreProtect database, so the following still needs a real session:

- in-game rendering (ghost boxes, selection wireframe, HUD) and the camera rig;
- end-to-end lookup against a populated CoreProtect database;
- timelapse export and the generated ffmpeg script.

## Next steps (W4 candidates)

- Server-side rollback *execution* (currently `ROLLBACK` is a client preview).
- Entity/player ghost trails alongside block ghosts.
- Per-request cancellation (`CANCELLED` already exists in the status enum).
- Hover inspector: point at a block and see its history without a full region scan.
- Migrate off the deprecated `HudRenderCallback` to `HudElementRegistry`.
