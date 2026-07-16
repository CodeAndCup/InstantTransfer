# InstantTransfer

**Seamless, zero-loading-screen server switching for Minecraft networks** (Velocity + Paper).

When a player is moved between two backend servers behind a proxy, vanilla Minecraft shows the
"Downloading terrain" screen (and on 1.20.2+, a full CONFIGURATION-phase world reload).
InstantTransfer removes that entirely: the world *morphs in place* — no screen, no disconnect,
no visible transition.

> 🇫🇷 Version française : [README_FR.md](README_FR.md)

## 🎬 Demo

**[▶️ Watch the demo video](docs/InstantTransfer-demo.mp4)** — a live switch between two backends:
no loading screen, the world simply morphs around the player.

## ⚠️ Experimental — do NOT use in production

This project patches **Velocity's internals** and rewrites live protocol traffic. It is a working
proof of concept, tested on a small 1.21.x network — not a hardened product. Expect breakage with:
untested Minecraft versions, mods, other packet-level plugins, cross-dimension travel, or different
registries/datapacks between backends. **Use it on test networks, at your own risk.**

## How it works

Three cooperating components (all are required):

| Component | Where | Role |
|---|---|---|
| `velocity-patch/` | replaces your Velocity jar | Skips the client CONFIGURATION phase on switches (absorbs the backend's config proxy-side via a known-packs echo) and sends **neither JoinGame nor Respawn** — the client never leaves PLAY. Announces entity-id pairs to the plugin. |
| `proxy-plugin/` | proxy `plugins/` (+ [packetevents-velocity](https://github.com/retrooper/packetevents)) | Tracks what the client sees (chunks, entities, effects, abilities). On arrival: lets the teleport pass, **freezes the player with client-side flight** until the landing chunk is delivered (no falling through unloaded ground), then removes the previous server's ghosts (entities, chunks, potion effects) and **swaps entity ids** (Velocity issue #251: op level / F3+F4, knockback, effects, attributes…). |
| `backend-agent/` | every backend's `plugins/` (+ [packetevents-spigot](https://github.com/retrooper/packetevents)) | Arrival grace: cancels fall damage, temporarily allows flight server-side (so the freeze can never kick), revokes it the instant the proxy signals the arrival is complete (plugin-message channel). |

Requirements for a seamless switch: backends on the **same Minecraft version** with **identical
registries/datapacks**, and (for now) the **same dimension**.

## Install

1. Build (JDK 21, Maven + Gradle):
   ```
   mvn -f pom.xml clean package                # proxy-plugin + backend-agent
   # Velocity patch: see velocity-patch/README.md (pre-built jar included)
   ```
2. Replace your Velocity jar with the patched one (`velocity-patch/`).
3. Proxy `plugins/`: `packetevents-velocity` + `proxy-plugin/target/InstantTransfer-*.jar`.
4. Each backend's `plugins/`: `packetevents-spigot` + `backend-agent/target/InstantTransferAgent-*.jar`.
5. Switch servers. There should be no screen at all.

### Flags / config

| What | Where | Default |
|---|---|---|
| Disable everything (vanilla Velocity behavior) | `-Dvelocity.seamlessSwitch=false` | enabled |
| Fallback mode: clean same-dimension Respawn (brief flash, no companion plugins needed) | `-Dvelocity.seamlessRespawn=true` | off (zero-screen) |
| Proxy plugin master switch, debug logs | `plugins/instanttransfer/config.properties` | enabled |
| Backend agent verbose logs | `plugins/InstantTransferAgent/config.yml` → `debug` | true |

## Repository layout

```
proxy-plugin/          Velocity plugin (packetevents): tracking, arrival freeze, ghost cleanup, id remap
backend-agent/         Paper plugin: arrival grace (fall/flight guard), coordination channel
velocity-patch/        Patched Velocity jar (velocity-instanttransfer.jar) + source diff + build instructions
docs/EXPERIMENTAL.md   R&D log: every approach tried, what failed and why, coverage audit, roadmap
```

## Status & known limits

- ✅ Zero screen, no fall damage, no ghost entities/chunks/effects/weather/GUIs/cooldowns/scoreboards, entity-id remap, gamemode resync — validated live on 1.21.x (Velocity dev/3.0.0, Paper 1.21.4).
- ⚠️ Same-dimension switches only; identical registries required (different datapacks → do not use).
- ⚠️ A player moving during the ~300 ms handshake may get a harmless server-side position correction (`moved too quickly` in logs).

Inspired by a private implementation showcased by Lodjo28 (no code published); this is an
independent from-scratch implementation.
