# Multi-version live matrix — RUNBOOK

> **Status: RIG ONLY.** Everything in `scripts/matrix/` is the test rig. The results table
> at the bottom stays **EMPTY** until the dedicated live session runs the matrix; only that
> session records outcomes (into this table and into the EXPERIMENTAL docs).

One patched Velocity proxy, two Paper backends, same Minecraft version on both sides.
Matrix pairs (A = B): **1.13.2, 1.16.5, 1.18.2, 1.20.1, 1.20.4, 1.21.4, latest**.

## 1. Topology

```
client :25565 ──► proxy (patched velocity-instanttransfer.jar + InstantTransfer plugin)
                    ├── backend-a  (Paper ${MC_VERSION_A}, agent + packetevents-spigot)
                    └── backend-b  (Paper ${MC_VERSION_B}, agent + packetevents-spigot)
```

- Backends are reachable by DNS alias `backend-a` / `backend-b` on the compose network
  (`matrix`), port 25565 — matches `[servers]` in `velocity.toml`.
- `try = ["backend-a", "backend-b"]`: fresh clients land on backend-a.
- Offline lab rig (`online-mode=false` everywhere) — never expose it.

## 2. Prerequisites (one-time)

```bash
cd <repo root>
mvn -q package          # -> proxy-plugin/target/InstantTransfer-0.1.0.jar
                        # -> backend-agent/spigot/target/instanttransfer-agent-0.1.0.jar
cd scripts/matrix
# drop the packetevents jars (exact names, see dropins/README.md):
#   dropins/spigot/packetevents-spigot.jar
#   dropins/velocity/packetevents-velocity.jar
```

Debug hooks are wired by mounts — verify they are active after bring-up:

- Proxy plugin `debug=true` comes from `proxy-config.properties` (mounted at
  `/app/plugins/instanttransfer/config.properties`). This is what enables the pending
  debug hooks already in code:
  - **verifyChunkPeek** — one-time live sanity vs wrapper decode for the first **10 chunk
    packets per connection**: log line
    `chunk peek verify for <player>: peeked (x,z) vs wrapper (x,z)`; the equality to
    confirm is peeked coords == wrapper coords on every line (no `*** MISMATCH`).
  - **movement peek probe** (`EntityIdRemapper.verifyMovementPeek`) — same discipline for
    the first **10 movement packets per connection**:
    `movement peek verify for <player>: peeked N vs wrapper N`; equality = identical ids,
    no MISMATCH.
- Agent `debug=true` comes from `agent-config.yml` (mounted at
  `/data/plugins/InstantTransferAgent/config.yml`; the shipped jar has no config.yml and
  would default to false) — gives `[COORD] received ...` / `[fall-guard] ...` lines.
- Optional DEBUG-level proxy logs (needed only for A6 below, since
  `flushed N deferred chunk unloads` is `logger.debug`):
  `EXTRA_JVM_OPTS="-Dlog4j.configurationFile=/app/log4j2-debug.xml"` when starting the proxy.

## 3. Per-pair bring-up

```bash
cd scripts/matrix
MC_A=1.13.2 MC_B=1.13.2 docker compose up -d     # long forms MC_VERSION_A= MC_VERSION_B= work too
docker compose logs -f backend-a                 # wait for "Done" + "For help"
docker compose logs -f backend-b                 # same
docker compose logs -f proxy                     # wait for "[InstantTransfer] enabled"
```

- First boot of a pair generates a fresh world (volumes are tagged with the version);
  allow extra time. Reruns of the SAME pair reuse that warm world (good for JFR).
- Version-tagged volumes also make cross-version world reuse impossible.
  `docker compose down -v` wipes the current pair's worlds.
- Use a client of the PAIR's own era whenever you judge anything visually.

## 4. Driving switches

Seamless switching is armed ONLY by the plugin command (plain Velocity `/server` takes the
normal switch path and must NOT be counted as a seamless test):

```
/transfer backend-b        # aliases: /instanttransfer, /it
/transfer backend-a        # ...and back
```

Per pair: log in once, then do at least **3 round trips** (6 switches), then capture:

```bash
docker compose logs proxy > run-<pair>.log       # e.g. run-1.13.2.log
docker compose logs backend-a >> run-<pair>.log
docker compose logs backend-b >> run-<pair>.log
```

## 5. Assertion checklist (scripted from logs, per pair)

Let S = number of `/transfer` switches performed.

| # | Assertion | Verbatim grep pattern | Expected |
|---|-----------|----------------------|----------|
| A1 | switch armed each time | `grep -c "\[InstantTransfer\] switch armed for" run-<pair>.log` | == S |
| A2 | ids announcement consumed exactly once per switch | `grep -c "\[InstantTransfer\] entity-id remap for" run-<pair>.log` | == S; zero `bad ids payload:` lines |
| A3 | zero forwarded JoinGame after first — every switch intercepted | >=1.20.2 pairs: `grep -c "\[seamless\] doSeamlessClientServerSwitch" run-<pair>.log`; <1.20.2 pairs: `grep -c "\[seamless\] doSeamlessLegacySwitch" run-<pair>.log` | == S. First login's JoinGame is the only one ever forwarded; a switch without its seamless-path line means the patch fell through -> FAIL |
| A4 | arrival chunk delivered, guard released | `grep "\[InstantTransfer\] seamless arrival for" run-<pair>.log` | S lines, each containing `(arrival chunk delivered)` or `(arrival chunk already loaded)`; this log IS the release() call |
| A5 | freeze engaged where needed | `grep -c "arrival teleport for .* freeze on" run-<pair>.log` | == S minus `(arrival chunk already loaded)` fast-path releases (those never froze) |
| A6 | deferred unload flush | `grep -o "flushed [0-9]* deferred chunk unloads" run-<pair>.log` | record every N (needs DEBUG log4j override); N must be far below the audit baseline (~one netty write per ghost chunk, hundreds per switch pre-change) |
| A7 | peek sanity (first-10 comparisons per connection) | `grep "chunk peek verify for" run-<pair>.log`; `grep "movement peek verify for" run-<pair>.log` | every line EQUALITY (`peeked == wrapper`); ANY `*** MISMATCH` -> stop, fix offset handling, do not trust fast paths |
| A8 | zero errors | `grep -cE "remap error|listener error|guard timeout|bad ids payload|MISMATCH" run-<pair>.log` | 0 |

Legacy-path expectation by era (mirrors A3): `[seamless] doSeamlessLegacySwitch` may appear
ONLY on pairs < 1.20.2 (1.13.2, 1.16.5, 1.18.2, 1.20.1); on 1.20.2+ its appearance is a FAIL
(the config-phase path must have handled it instead).

## 6. Visual pass (manual)

Mandatory pairs: **1.16.5, 1.20.1, 1.20.4, latest** (native-era client). Per switch, confirm:

- [ ] NO loading screen / "joining world" flash — world morphs in place
- [ ] terrain continuous: no holes, no duplicated/ghost chunks around the player
- [ ] entities persist; no doubled or invisible mobs/players
- [ ] hotbar/inventory/armor intact; held item correct
- [ ] active potion effects keep their HUD icons; cleared effects leave NO swirl
- [ ] fall damage: landing after switch hurts normally (agent grace prevents false kicks)
- [ ] spectator noclip still works on the new backend (own tab-entry fix)
- [ ] scoreboard objectives/teams from old server gone; new server's present
- [ ] weather matches the NEW server; chat and tab list intact

## 7. Extra cases (latest pair only)

| Case | Procedure | Expected |
|------|-----------|----------|
| Cross-dimension fallback | Walk through a nether portal on the current backend; once in the nether run `/transfer backend-b` | Brief vanilla loading screen (accepted fallback), log `[InstantTransfer] cross-dimension switch for ... Respawn fallback`; arrive safely in backend-b's overworld; scoreboard/effects clean |
| Death respawn relay | On backend-a console `kill <player>`, respawn normally, then immediately `/transfer backend-b` | Seamless arrival as usual; death screen state fully cleared; no ghost effects/scoreboard; dimension tracking stays consistent afterwards |
| Portal relay | Enter a nether portal and fire `/transfer` during the portal teleport window | Either clean seamless arrival or clean Respawn fallback; NO kick, no stuck-in-portal desync, no position-correction loop beyond guard timeout |

## 8. JFR performance capture (on the 1.21.4 pair)

```bash
cd scripts/matrix
MC_A=1.21.4 MC_B=1.21.4 \
EXTRA_JVM_OPTS="-XX:StartFlightRecording=filename=/app/proxy-after.jfr,duration=10m,settings=profile" \
docker compose up -d proxy
# drive load for the full 10 minutes:
#   - bot/client A crossing switches every ~10 s (/transfer backend-b, /transfer backend-a, ...)
#   - second account idling in a chunk-heavy area (pre-generate by flying around first)
docker compose cp proxy:/app/proxy-after.jfr .
```

Analyze (`jfr print --events jdk.ExecutionSample,jdk.AllocationRealloc,proxy-after.jfr` or JMC):

- p99 listener cost proxy metric: zero `remap error` / guard anomalies during capture
  (assertion A8 must hold throughout);
- hot methods: after-state must be dominated by `ChunkCoordPeek` / `LeadingVarInt`
  raw-buffer peeks — NOT wrapper constructors inside `SeamlessPacketListener.onPacketSend`
  / `EntityIdRemapper.onPacketSend`;
- allocation rate: compare against the pre-change baseline if a pre-change jar is available
  at `velocity-patch/archive/`;
- **netty writes per switch** (the P3 delta): sum of UnloadChunk sends per switch = the A6
  `flushed N deferred chunk unloads` numbers (before-state: audit baseline ~one write per
  ghost chunk, hundreds per switch). Record before AND after in the results table.

## 9. Results table — TO FILL DURING THE LIVE SESSION

> EMPTY BY DESIGN. Fill every cell while running each pair; then copy outcomes into the
> EXPERIMENTAL docs (Task 12 live session). Do not commit guesses here.

Legend — legacy-path log seen: expected YES only for <= 1.20.1 pairs.

| Version pair | Switch ok? | Screen? | Ghosts clean? | Freeze ok? | Unload flush N | Legacy-path log seen | Notes |
|--------------|------------|---------|---------------|------------|----------------|----------------------|-------|
| 1.13.2       |            |         |               |            |                |                      |       |
| 1.16.5       |            |         |               |            |                |                      |       |
| 1.18.2       |            |         |               |            |                |                      |       |
| 1.20.1       |            |         |               |            |                |                      |       |
| 1.20.4       |            |         |               |            |                |                      |       |
| 1.21.4       |            |         |               |            |                |                      |       |
| latest       |            |         |               |            |                |                      |       |

## 10. Teardown & troubleshooting

```bash
docker compose down -v      # stop + wipe current pair's worlds
MC_A=... MC_B=... docker compose up -d    # next pair
```

- **Guard timeout lines** (`guard timeout for ... position correction`): arrival chunk never
  came — check backend logs for errors; not a rig bug if reproducible across reruns.
- **peek MISMATCH (A7)**: header-offset assumption broken for this packetevents slicing —
  do NOT ship; fix offset handling / adapter tables (see velocity-patch maintenance
  contract), rebuild, rerun.
- **Era-specific failure**: adjust the adapter tables per the maintenance contract
  (docs §7), rerun `scripts/check-protocol-tables.sh` + `scripts/validate-patch-hunks.sh`,
  rebuild, rerun the pair.
- Backend console without attaching: `echo "<cmd>" | docker compose exec -T backend-a rcon-cli`.

