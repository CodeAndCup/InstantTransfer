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
| **Entity-id remap** (Velocity issue #251: F3+F4 op level, knockback, effects, attributes) | Synthetic ids message from the patch + **directional** id rewrite in the plugin (backendId→clientId server→client, clientId→backendId client→server) | patch + proxy plugin |
| **Gamemode resync** (broke eating with an infinite-chew loop) | Gamemode carried in the ids message + ChangeGameState on arrival | patch + proxy plugin |
| **Cross-dimension fallback** (zero-screen unsafe if dim differs) | Patch tracks client dim; auto-fallback to Respawn KEEP_ALL_DATA if backend dim ≠ client dim | `velocity-patch/` |
| **Timeout rollback fix** (3 s guard timeout caused rollback) | On timeout: send position correction to absolute arrival pos + keep flight enabled; extend guard. Only works for absolute teleports. | proxy plugin |

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
   ├─ when the arrival chunk lands (or timeout): restores real abilities,
   │  removes ghosts (entities/chunks/effects + swirl metadata), resyncs gamemode,
   │  tells the agent to end its grace (coord channel)
   ├─ **entity-id directional rewrite**: server→client rewrites backendId→clientId;
   │  client→server rewrites clientId→backendId (no swap on other entities)
   └─ **timeout guard**: configurable (default 5 s); on timeout sends position correction
      to absolute arrival coords + keeps flight; extends guard until chunk arrives

[Backend agent]  backend-agent/  (on EVERY backend, + packetevents-spigot)
   ├─ fall-guard: cancels FALL/SUFFOCATION damage for a **1 s** grace (was 3 s)
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
| **Guard timeout (ms)** | `plugins/instanttransfer/config.properties` → `guard-timeout-ms` | **5000** |
| Agent verbose logs ([DIAG-BACKEND], [COORD], [fall-guard]) | backend `plugins/InstantTransferAgent/config.yml` → `debug` | false |

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
| Old server's scoreboard objectives/teams | THREE belts: (1) REMOVE on release; (2) purge at switch time (ids message) — the scoreboard is connection-scoped client-side and a destination without its own scoreboard would never trigger any re-CREATE; (3) a re-CREATE of a client-known name gets a REMOVE injected first, keyed on the ACTIVE set (mirror of client truth), not on ghost bookkeeping — the vanilla client THROWS on duplicate objective CREATE → "network error" kick | ✅ |
| Ghost weather (old server's rain) | CHANGE_GAME_STATE weather tracking → END_RAINING + zeroed rain/thunder levels on release if the new server sent no weather | ✅ |
| Open GUI/chest during the switch | OPEN_WINDOW/OPEN_HORSE_WINDOW tracking → CloseWindow on release | ✅ |
| Own tab-list entry (the client caches its PlayerInfo object inside the player entity; a remove/re-add cycle strands the cache → spectator noclip breaks, isSpectator/isCreative read a stale gamemode) | own-UUID filtered out of PLAYER_INFO_REMOVE + re-ADDs downgraded to in-place updates of the surviving entry | ✅ |
| Different dimension between servers | patch compares the dimension type at each switch → automatic real-Respawn fallback (correct vanilla reload, brief screen); the plugin detects the Respawn and stands its machinery down; backend-relayed Respawns (portals, /execute in … tp, death) also update the tracked dimension | ✅ (fallback, not zero-screen: a client level cannot change sky/height in place) |
| Ghost entities from the previous server | destroyed at switch time (ids message): a `DestroyEntities` batch in client-space ids, world-independent, fired long before the arrival chunk — NOT only at release (on real networks the release leg can be starved; see §7 incident) | ✅ |
| Backends older than 1.20.2 (no CONFIGURATION phase) | PLAY-state switch in the patch: backend JoinGame intercepted, never forwarded (see §7); Respawn decodable on every era via the StateRegistry `map(...)` table | ✅ implemented + unit-covered, live matrix pending |
| Ghost effect swirls on eras with unverified living-entity metadata indices (< 1.20.2) | REMOVE_ENTITY_EFFECT still sent (HUD icon cleared); the swirl metadata reset is gracefully skipped instead of guessing historical indices — leftover swirls fade at the effect's natural expiry | ✅ (accepted degradation) |

## 6. Remaining work

1. Broader version range: implemented — supported range is now MC **1.13 → latest** (see §7);
   ≥ 1.20.2 is live-validated; the < 1.20.2 PLAY-state path awaits the live matrix, then BungeeCord.
2. Deployment note: each switch re-sends the player's whole view distance (~400 chunk packets);
   size backend heaps accordingly (≥ 2 GB recommended) — an undersized heap shows up as GC stalls
   ("Can't keep up!") and eventually OutOfMemoryError during chunk saves.

## 7. Multi-version support & performance pass (2026-08-25)

### One design, two eras
The zero-screen switch now works on any homogeneous network from MC **1.13 to latest**, via two
paths behind the same `doSwitch()`:
- **≥ 1.20.2 — config-phase absorption**: the backend sends its CONFIGURATION phase and the patch
  absorbs it entirely proxy-side (KnownPacks echoed, RegistrySync/Tags swallowed); the client
  never leaves PLAY.
- **1.13 – 1.20.1 — PLAY-state switch**: these versions have no configuration phase at all, so
  there is nothing to absorb — the patch simply intercepts the backend's JoinGame in
  `doSeamlessLegacySwitch` and never forwards it. Strictly *simpler* than the ≥ 1.20.2 path:
  no 3-party handshake (client↔Velocity↔backend) to choreograph, no known-packs echo.
Both paths rely on the Respawn decode table (`StateRegistry`, one `map(...)` line per version)
so client-dimension tracking — and with it the cross-dimension Respawn fallback — work on every
era. The companion plugins follow: the agent ships Java 8 bytecode / `api-version: 1.13` so it
loads on the oldest supported servers.

### Graceful degradation for unverified metadata eras
Rule: never guess historical metadata indices. On eras whose living-entity effect-color/particle
indices have not been verified (< 1.20.2), the swirl-particle metadata reset is skipped;
REMOVE_ENTITY_EFFECT is still sent, so the HUD effect always clears — only the cosmetic swirl may
linger until the effect would naturally expire. Accepted degradation, never corruption.

### Performance pass
Qualitative deltas shipped on this branch:
- raw chunk-coordinate reads instead of boxed wrapper lookups on hot paths;
- movement-packet peek instead of full decode where only movement is needed;
- deferred ghost-chunk unloads, deduped against the destination's resends (a chunk the new server
  re-sends is no longer unloaded then immediately re-delivered);
- bounded loaded-chunk-tracking memory (tracking pruned on far teleports).

### Live validation & the packetevents-skew incident (2026-08-25, Paper 1.21.4 ×2)
The first real-network run exposed a protocol skew between **packetevents 2.13.0 and Paper
1.21.4**, with three distinct symptoms sharing one root:
1. `ENTITY_METADATA` parsing threw on an unknown metadata type id (32) — PE's V_1_21_4 registry
   is incomplete;
2. event buffers sometimes still carry the leading packet-id VarInt (proven empirically by the
   chunk-header offset probe), so wrapper parses misalign by one byte — and a misaligned parse
   that still "succeeds" re-encodes into a TRUNCATED frame (`set_entity_motion`,
   `entity_position_sync` at 3 bytes) that hard-crashes the vanilla client decoder.

**Answer: stop using wrappers for the affected types.** `RawEntityIdRewriter` swaps ONLY the
leading entity-id VarInt directly in the event buffer, testing both slicing hypotheses (with /
without pid prefix), saving the original bytes first, verifying the write, and rolling back on
any anomaly — a failed rewrite degrades to pass-through, never to corruption. Applied to
METADATA / VELOCITY / TELEPORT / POSITION_SYNC; the remaining wrappers (effects, attributes,
equipment…) have shown no skew and stay wrapper-based. Knockback and self-metadata remapping are
fully restored this way, without waiting on a packetevents upgrade.

**Second incident: unmanaged switches.** The ghost lifecycle (snapshot → purge → release) was
armed only for `/transfer`/API requests; a plain `/server` switch got the zero-screen patch and
the id remap but NO lifecycle — previous-server entities survived on the destination forever,
and every release-side cleanup leg appeared dead. Rule now: EVERY post-join switch arms
snapshot + guard; the instant-transfer flag only gates the API events. This also resurrected the
release log line (`seamless arrival … removed N ghost chunks`) that had never been observed.

**Scoreboard hardening** came out of the same run: connection-scoped client state means a
destination WITHOUT its own scoreboard never triggers any re-CREATE, so release-time removal
alone can leave a stale sidebar visible forever. Three belts now: ids-time purge (idempotent),
active-keyed duplicate-CREATE guard, release cleanup.

### Measured results
Live single-player rig, Paper 1.21.4 ×2 backends behind the patched Velocity, repeated S1↔S2
round-trips (2026-08-25):
- freeze window (arrival teleport → unfreeze): **≈160–260 ms** per switch;
- arrival detection: "arrival chunk delivered" on 100 % of observed switches;
- ghost cleanup at release: **97–472 ghost chunks destroyed per switch** (view-distance
  dependent), 4 ghost entities purged at switch time, 1 scoreboard objective purged;
- stability: zero crashes / zero kicks across the final build's repeated round-trips, including
  knockback hits, a death respawn relay and a cross-dimension fallback.
Matrix runs for < 1.20.2 eras remain pending (see RUNBOOK).

### Per-release maintenance contract
Supporting a newly released Minecraft version costs one line: add the matching `map(...)` entry
to the Respawn decode table in `velocity-patch/seamless.patch` (`StateRegistry`), run
`scripts/check-protocol-tables.sh` (guards the table against lagging behind packetevents — via a
derived constant with an `EXPECTED_NEWEST` fallback documented in the script)
+ `scripts/validate-patch-hunks.sh` (validates the patch hunk counts), then rebuild the jar per
[velocity-patch/README.md](../velocity-patch/README.md).
