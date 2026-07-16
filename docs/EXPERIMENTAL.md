# EXPERIMENTAL — R&D notes & design rationale

> 🇫🇷 Version française : [EXPERIMENTAL_FR.md](EXPERIMENTAL_FR.md)

**This whole project is experimental.** It patches Velocity's internals and rewrites live protocol
traffic. It has been validated on a small 1.21.x test network only — do **not** run it on a
production server. This document is the project's R&D log: what was tried, what failed and why,
how the current design works, and what remains.

Goal: switch a player between backend servers with **zero visible transition** — no
"Downloading terrain" screen, no world reload, no disconnect. Target: MC 1.21.x, Velocity + Paper.

## 1. What is PROVEN (locks that were broken — don't revisit)

| Achievement | How | Where |
|---|---|---|
| **CONFIGURATION phase eliminated** (the #1 cause of the switch screen on 1.20.2+) | Velocity patch: the client never leaves PLAY; the backend's config is absorbed proxy-side (known-packs echo, registries swallowed) | `velocity-patch/` |
| **No disconnect** during the switch | Confirmed in live tests | — |
| **Ghost cleanup** (old server's mobs/chunks/potion effects) | packetevents tracking proxy-side + DestroyEntities/UnloadChunk/RemoveEntityEffect + particle-metadata reset on arrival | proxy plugin |
| **No arrival fall damage / fall-through-ground** | Arrival flight-freeze (proxy) + grace window (agent) | proxy plugin + agent |
| **Entity-id remap** (Velocity issue #251: F3+F4 op level, knockback, effects, attributes) | Synthetic ids message from the patch + symmetric id swap in the plugin | patch + proxy plugin |
| **Gamemode resync** (broke eating with an infinite-chew loop) | Gamemode carried in the ids message + ChangeGameState on arrival | patch + proxy plugin |

## 2. Approaches TRIED and REJECTED (do not retry)

| Approach | Verdict | Root cause |
|---|---|---|
| Cancel the JoinGame in a pure packetevents proxy plugin (v0.1) | ❌ Disconnect | The client had already been switched into CONFIGURATION; a proxy plugin only sees the client connection and cannot remove the 3-party config handshake (client↔Velocity↔backend) |
| "Fake instant" (chunk preloading alone, no patch) | ❌ Screen still shows | The screen comes from the PLAY→CONFIG→PLAY transition itself, not from waiting for chunks |
| Transfer packets (TransferProxy) | ❌ Trap | It is a full reconnect → guaranteed screen |
| Same-dimension Respawn (keptData=0, then 0x03) | ❌ ~0.5 s flash | The Respawn ITSELF triggers `ReceivingLevelScreen` regardless of keptData — it is also what makes the client wait for the arrival chunk (screen and anti-fall-through are the same vanilla mechanism) |
| Raw no-Respawn (send nothing, no accompaniment) | ❌ Zero screen BUT fall damage + ghost mobs + sinking through the ground | Without a Respawn nothing resets physics or tears down the old world |
| **Position-hold** (cancel the arrival teleport, replay it when the chunk arrives) | ❌ Infinite log spam + broken placement | 4 structural flaws: (1) `user.sendPacket()` re-enters the listener synchronously → infinite recursion at timeout; (2) cancelling the teleport breaks Paper's teleport-confirm handshake; (3) old-server chunks left in the tracker falsified the "chunk already loaded" check when spawn areas overlap; (4) relative coordinates unhandled |

## 3. CURRENT architecture (3 components)

```
[Patched Velocity]  velocity-patch/velocity-instanttransfer.jar
   ├─ config skip (client stays in PLAY)                 ← default ON
   ├─ zero screen: sends NEITHER JoinGame NOR Respawn    ← default ON
   │  (fallback: -Dvelocity.seamlessRespawn=true → clean Respawn, brief flash)
   └─ announces "clientId:backendId:gamemode" to the plugin (synthetic plugin message)

[Proxy plugin]  proxy-plugin/  (in the proxy's plugins/, + packetevents-velocity)
   ├─ tracks client-visible state: entities, chunks, self potion effects, abilities
   ├─ on arrival: the teleport PASSES (handshake intact) + FLIGHT FREEZE injected
   │  (the client hovers in place → no falling, no sinking while chunks load)
   ├─ when the arrival chunk lands (or 3 s timeout): restores real abilities,
   │  removes ghosts (entities/chunks/effects + swirl metadata), resyncs gamemode,
   │  tells the agent to end its grace (coord channel)
   └─ entity-id SWAP both ways (self packets reach the client; reverse collisions shielded)

[Backend agent]  backend-agent/  (on EVERY backend, + packetevents-spigot)
   ├─ fall-guard: cancels FALL/SUFFOCATION damage for a 3 s grace
   └─ temporary allowFlight during the grace (else the freeze would kick
      "Flying is not enabled"), revoked instantly on the proxy's unfreeze message
```

### Why flight-freeze instead of position-hold
For an **arbitrary** arrival position (last known location, possibly months old), the arrival chunk
must be waited for — there is no way around it. Position-hold hid the wait by withholding the
teleport, but withholding the teleport breaks the protocol. The flight freeze hides the same wait
**without withholding anything**: every packet flows normally; we only add a temporary client
state (flight) that neutralizes gravity. It is the least invasive mechanism possible.

## 4. Flags & config

| What | Where | Default |
|---|---|---|
| Config skip | `-Dvelocity.seamlessSwitch=false` to disable | ON |
| Respawn mode (robust fallback, brief flash) | `-Dvelocity.seamlessRespawn=true` | OFF (= zero screen) |
| Proxy plugin master switch / debug | `plugins/instanttransfer/config.properties` | enabled |
| Agent verbose logs ([DIAG-BACKEND], [COORD], [fall-guard]) | backend `plugins/InstantTransferAgent/config.yml` → `debug` | true |

## 5. Coverage audit — "everything JoinGame/Respawn used to reset for us"

| Client state | Covered by | Status |
|---|---|---|
| Old world's chunks + entities | ghost cleanup (plugin) | ✅ |
| Player entity id | id swap (patch + plugin) | ✅ |
| Potion effects (HUD icons + swirl particle metadata) | ghost effects + metadata 10/11 reset | ✅ |
| Fall distance / velocity | flight freeze + agent fall-guard | ✅ |
| Abilities (flight, speed, FOV) | tracked & replayed on release | ✅ |
| Gamemode | ChangeGameState(3) on release, carried in the ids message | ✅ (was the infinite-eating bug) |
| Health / food / XP / held slot / inventory | re-sent by the new server on its first tick (dirty-sync) | ✅ automatic |
| Tab list, boss bars | handled by Velocity on switch | ✅ automatic |
| View/simulation distance, recipes, advancements | re-sent at join | ✅ automatic |
| Item cooldowns (ender pearl, shield…) | SET_COOLDOWN tracking → zeroed on release (a real join never carries them over) | ✅ |
| Old server's scoreboard objectives/teams | SCOREBOARD_OBJECTIVE/TEAMS tracking → REMOVE on release (dropping an objective also drops its scores + display slot; dropping a team drops memberships) | ✅ |
| Ghost weather (old server's rain) | CHANGE_GAME_STATE weather tracking → END_RAINING + zeroed rain/thunder levels on release if the new server sent no weather | ✅ |
| Open GUI/chest during the switch | OPEN_WINDOW/OPEN_HORSE_WINDOW tracking → CloseWindow on release | ✅ |
| Different dimension between servers | — | ❌ unsupported (auto Respawn fallback to implement) |

## 6. Remaining work

1. The ❌ row of the audit above (cross-dimension fallback).
2. Broader version range (currently developed and tested against 1.21.x), then BungeeCord.
3. Deployment note: each switch re-sends the player's whole view distance (~400 chunk packets);
   size backend heaps accordingly (≥ 2 GB recommended) — an undersized heap shows up as GC stalls
   ("Can't keep up!") and eventually OutOfMemoryError during chunk saves.
