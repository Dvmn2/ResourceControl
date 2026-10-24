# ResourceControl

Fabric client mod that downloads and applies the resource packs a server assigns to the player. Companion plugin: `ResourceControlPlugin`.

## Requirements
- Minecraft 1.21.11, Java 21
- Fabric Loader >= 0.19.5, Fabric API (built against `0.141.6+1.21.11`)
- Companion plugin `ResourceControlPlugin` >= 2.0.0 (protocol 2) on the server

## Build and run
```bash
./gradlew build      # build/libs/ResourceControl-<version>.jar
./gradlew runClient  # Minecraft 1.21.11 dev client
```

## Architecture
Package `net.dvmn2.resourcecontrol.client` (source set `client`; `src/main` holds only `fabric.mod.json`):

| Class | Responsibility |
|---|---|
| `ResourceControlClient` | entry point: registers payloads, receivers, events and the command |
| `ResourceControlConfig` | player settings (`config/resourcecontrol.json`); the server cannot override them |
| `command.ResourceControlCommand` | `/rc off\|on\|status\|clear\|shaders allow\|block` |
| `mixin.ResourcePackManagerMixin` | adds our pack profiles to the client's `ResourcePackManager`; no logic of its own |
| `network.LimitPayload`, `SyncPayload`, `StatusPayload` | packets and their codecs (format in Javadoc) |
| `network.StatusReporter` | sends status messages to the server |
| `network.ResourceControlProtocol` | `PROTOCOL` constant |
| `pack.PackCache` | cache paths, zip inspection, cache cleanup |
| `pack.PackDownloader` | safe https download with SHA-1 and size verification, stall watchdog |
| `pack.PackProfiles` | builds pack profiles, fixes enabled order, reloads resources |
| `pack.PackValidator` | validates a received pack set before any download |
| `state.ActivePacks` | persistent and session pack sets; owns `state.json` |
| `sync.SyncManager` | processes server messages, downloads in the background, applies the result |
| `sync.ConsentManager` | asks the player to allow downloads of a size and remembers the answer |
| `sync.ConsentStore` | per-server consents on disk (`consent.json`) |
| `sync.ReloadManager` | starts resource reloads at a safe moment |
| `util.AtomicFiles`, `Overlay` | atomic file writes; action bar messages from any thread |

Threading:
- Message handlers, `ConsentManager`, `ReloadManager` and the command run on the client thread.
- Downloads and zip inspection run on the single daemon thread `ResourceControl-Downloader`; results return with `client.execute`. A stale result is dropped by comparing an `AtomicInteger` generation counter.
- A daemon scheduler thread (`ResourceControl-Watchdog`) aborts downloads that stall for 30 seconds.

Lifecycle:
1. `onInitializeClient`: create `ReloadManager` and `SyncManager`, register payload types, receivers, `DISCONNECT`, `CLIENT_STARTED`, tick event and the command.
2. `CLIENT_STARTED`: remember the applied pack set, reorder enabled packs, clean the cache in the background.
3. On a server message: validate, ask for consent if needed, download, `ActivePacks.set`, request a reload.
4. `DISCONNECT`: cancel work, reset consent state, drop session packs, request a reload after the world is closed.

## Public API
None.

## Protocol
| Channel | Direction | Protocol | Format |
|---|---|---|---|
| `dvmn2:rc_limit` | S2C | 2 | `LimitPayload` Javadoc |
| `dvmn2:rc_sync` | S2C | 2 | `SyncPayload` Javadoc |
| `dvmn2:rc_status` | C2S | 2 | `StatusPayload` Javadoc |

Compatibility rules:
- The first field of every packet is a VarInt protocol version; `ResourceControlProtocol.PROTOCOL` must equal the plugin's `SyncManager.PROTOCOL` and is increased on every format change.
- A packet with another protocol is ignored silently. Mod 2.x works only with plugin 2.x.
- Packets that fail to decode become a packet with `INVALID_PROTOCOL` (the rest of the buffer is skipped), so a malformed message never disconnects the player.
- Registering the receivers makes the server see the mod as installed.

## Data and files
| File | Format | Version | Content |
|---|---|---|---|
| `config/resourcecontrol.json` | JSON | none | `enabled` (default `true`), `blockShaders` (default `false`) |
| `<gameDir>/resourcecontrol/state.json` | JSON | `version: 1` | saved ("persistent") packs: `name`, `sha1`, `size`, `priority` |
| `<gameDir>/resourcecontrol/consent.json` | JSON | `version: 1` | per-server approved pack size in bytes |
| `<gameDir>/resourcecontrol/cache/<sha1>.zip` | zip | n/a | downloaded packs; `.part` files are unfinished downloads |

All files are written atomically (temp file and move). A corrupt `state.json` loses the saved packs and a corrupt `consent.json` means "no consents"; the game still starts. Cache files unused for 14 days are deleted (the timestamp of a pack in use is refreshed at most once a day). There is no migration from 1.x.

## Conventions and constraints
- File names in the cache are built only from a validated SHA-1; packs are mounted straight from the zip and never unpacked, so network data cannot influence a path.
- Only `https` is accepted; the HTTP client does not follow redirects to `http`. The declared size is checked against the size the player approved before the download, and the actual size and SHA-1 afterwards.
- The consent decision is stored only on the client and is never sent to the server. The size limit from the server is a request, not a permission.
- Resource reloads are never started from the `DISCONNECT` event: callers only mark a reload as needed and `ReloadManager#onTick` starts it later (reloading while the world is torn down can leave the loading screen stuck). The applied signature is fixed only when the reload really starts.
- `PackProfiles#mergeInto` catches errors, logs once and disables the feature until restart instead of breaking the resource pack screen.
- `/rc off` clears all packs and ignores the server; `/rc on` replays the last server messages kept in memory. `blockShaders` also filters saved packs mounted at game start.
- The command root is the same as the server plugin's; Fabric forwards subcommands unknown to the client to the server.
- The mod renders no effects of its own, so it has no intensity or flashing settings.

## License
All rights reserved.
