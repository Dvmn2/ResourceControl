# ResourceControl

Fabric client mod for Minecraft 1.21.11. Applies resource packs that the server plugin
[ResourceControlPlugin](https://github.com/Dvmn2/ResourceControlPlugin) has assigned to a specific player.

The mod has no standalone functionality. Without the plugin on the server it does nothing.

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | see `gradle.properties` (`loader_version`) |
| Fabric API | required |
| Environment | client only |
| Java | 21 |

## Installation

1. Install Fabric Loader and Fabric API.
2. Place `ResourceControl-<version>.jar` in the `mods` folder.
3. Connect to a server that runs ResourceControlPlugin.

Servers with `require-client-mod` enabled disconnect players who do not have the mod.

## How it works

1. On join, the mod announces itself to the plugin (registration of the `dvmn2:rc_sync` channel).
2. The plugin sends the player's full pack set: name, HTTPS URL, SHA-1, size, priority, `persistent` flag.
3. The mod downloads packs that are not in the cache. Progress is shown in the action bar.
4. After downloading, the mod mounts the packs on top of the vanilla resources and reloads resources once.
5. The mod reports a status to the plugin: downloading, ready, or failed with a reason.

Each new plugin message replaces the previous set. If the set has not changed, no reload is performed.

### Layer order

Vanilla resources, then the vanilla server resource pack, then ResourceControl packs in ascending priority.
A pack with a higher priority overrides a pack with a lower priority.

### The persistent flag

| Flag | Behavior |
|---|---|
| `persistent = true` | The pack is saved to `state.json` and mounted on every game launch. It stays active after leaving the server. Allows changing the main menu panorama. |
| `persistent = false` | The pack is active only while connected to the server and is removed on disconnect. |

A saved pack is replaced or removed when any server with the plugin sends a set that no longer contains it.

## Files on disk

Directory `<game dir>/resourcecontrol/`:

| Path | Purpose |
|---|---|
| `cache/<sha1>.zip` | Cache of downloaded packs. Unused files older than 14 days are deleted. |
| `state.json` | List of persistent packs mounted at launch. |

## Client command

| Command | Action |
|---|---|
| `/rcclear` | Removes all saved persistent packs and reloads resources. |

## Security

- Only HTTPS URLs are accepted. Redirects to HTTP are rejected.
- Maximum pack size is capped at 256 MB regardless of the value sent by the server.
- A file is accepted only if both SHA-1 and size match.
- The cache file name is derived from the SHA-1. The URL and pack name do not affect the path.
- Archives are not extracted, so path traversal is not possible.
- If the server disallows shaders, packs containing `assets/*/shaders` are rejected.
- Packs are mounted as required and pinned. They cannot be disabled in the resource pack menu.

## Limitations

- Reloading resources when a set is applied briefly freezes the client.
- Persistent packs remain active on other servers until the set is replaced.
- Core shaders may conflict with Sodium and Iris.

## Building

```
./gradlew build
```

Output: `build/libs/ResourceControl-<version>.jar`.

## Protocol

Channel `dvmn2:rc_sync` (server → client):

```
VarInt  protocol = 1
Boolean allowShaders
VarInt  count (maximum 64)
count × { String name, String url, String sha1, VarLong size, VarInt priority, Boolean persistent }
```

Channel `dvmn2:rc_status` (client → server):

```
VarInt protocol
VarInt state    0 — downloading, 1 — ready, 2 — failed
String message
```

## License

All Rights Reserved.
