# dropins/ — jars YOU must place here (not committed: third-party binaries)

The compose file mounts two jars from this folder by EXACT filename. Download both from
the packetevents releases page (https://github.com/retrooper/packetevents/releases) and
rename them precisely:

| File (exact name)                          | Mounted to                                   | Used by                    |
|--------------------------------------------|----------------------------------------------|----------------------------|
| `velocity/packetevents-velocity.jar`       | `/app/plugins/packetevents-velocity.jar`     | proxy (patched Velocity)   |
| `spigot/packetevents-spigot.jar`           | `/data/plugins/packetevents-spigot.jar`      | BOTH Paper backends        |

Notes:
- Pick a packetevents build supporting the full matrix range (2.x releases cover 1.13 → latest).
- If a file is missing when you first run `docker compose up`, Docker creates an empty
  DIRECTORY at that path instead — stop, delete the stray directory inside the container
  path (`docker compose down`, then remove the named volume if it got polluted), drop the
  real jar in, and start again.
- The InstantTransfer jars themselves are NOT downloaded: build them with `mvn -q package`
  at the repo root; the compose file mounts them straight out of the Maven targets.
