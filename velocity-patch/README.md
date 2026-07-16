# Velocity patch — seamless switch (zero screen)

A patch of **Velocity's core** (branch `dev/3.0.0`, v3.6.0-SNAPSHOT) enabling zero-loading-screen
server switches. Full system overview: see the repository root [README](../README.md) and
[docs/EXPERIMENTAL.md](../docs/EXPERIMENTAL.md).

> 🇫🇷 Version française : [README_FR.md](README_FR.md)

- `velocity-instanttransfer.jar` — pre-built patched proxy jar (replaces your Velocity jar).
- `seamless.patch` — the full source diff, if you want to review or rebuild it.

## What the patch does

1. **Config-phase skip** — `ClientPlaySessionHandler.doSwitch()`: on a switch (client ≥ 1.20.2),
   `switchToConfigState()` is no longer called — the client **stays in PLAY**.
   `ConfigSessionHandler` absorbs the backend's configuration proxy-side: `KnownPacks` is echoed
   back to the backend, `RegistrySync`/`Tags`/config plugin messages are swallowed,
   `FinishedUpdate` is acknowledged to the backend without touching the client.
2. **Zero screen** — `doSeamlessClientServerSwitch()` sends **neither JoinGame nor Respawn** to
   the client. The world morphs in place from the new server's chunks + teleport. The companion
   proxy plugin (InstantTransfer) freezes the player during the chunk gap, cleans up ghosts and
   swaps entity ids; the backend agent guards against fall damage and flight kicks.
3. **Ids/gamemode announcement** — at every seamless switch the patch writes a synthetic plugin
   message (`instanttransfer:ids`, payload `clientId:backendId:gamemode`) that the proxy plugin
   intercepts (the client never sees it) to drive the entity-id remap and the gamemode resync.

## Flags (JVM property or environment variable)

| Flag | Effect | Default |
|---|---|---|
| `-Dvelocity.seamlessSwitch=false` | Disable EVERYTHING (vanilla Velocity behavior) | seamless **ON** |
| `-Dvelocity.seamlessRespawn=true` | Robust fallback: send a same-dimension KEEP_ALL_DATA Respawn (brief "loading terrain" flash, but a full clean client reset, no companion plugins needed) | **OFF** (= zero screen) |

## Install

1. Replace your Velocity jar with `velocity-instanttransfer.jar` (back up the old one).
2. Proxy `plugins/`: `packetevents-velocity` + the InstantTransfer proxy plugin
   (with `seamless-enabled=true` in `plugins/instanttransfer/config.properties`).
3. Every backend's `plugins/`: `packetevents-spigot` + the InstantTransferAgent plugin.
4. No flag needed (zero screen is the default).

## Logs to watch during a switch

- Proxy core: `[seamless] doSwitch ... seamless=true` then
  `[seamless] doSeamlessClientServerSwitch: zero-screen ...`
- Proxy plugin: `[InstantTransfer] switch armed ...`, `entity-id remap ... (swap active)`,
  `arrival teleport ... freeze on`, then
  `seamless arrival for X (arrival chunk delivered): removed N ghost entities, M ghost chunks, K ghost effects`
- Backend (agent `debug=true`): `[fall-guard] arrival grace armed ...`, `[COORD] received ... unfreeze`

## If something breaks

- `-Dvelocity.seamlessRespawn=true` → clean fallback with a brief flash (keeps the config skip).
- `-Dvelocity.seamlessSwitch=false` → full vanilla Velocity behavior.

## Rebuild from source

```
git clone https://github.com/PaperMC/Velocity && cd Velocity
git fetch origin dev/3.0.0 && git checkout FETCH_HEAD
git apply /path/to/seamless.patch
./gradlew :velocity-proxy:shadowJar
# -> proxy/build/libs/velocity-proxy-3.6.0-SNAPSHOT-all.jar
```
