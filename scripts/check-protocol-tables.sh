#!/usr/bin/env bash
# Fails when seamless.patch's StateRegistry maps lag behind packetevents' newest known
# Minecraft version (per-release maintenance contract, spec section 4.2).
#
# NOTE (2026-08-24, verified in this environment): dynamic extraction cannot run here —
# `unzip` is not installed (WSL/Git Bash), so the class constant pool can't be dumped as
# scripted; the packetevents jars live in the WINDOWS Maven repo (/mnt/c/Users/mathi/.m2,
# newest packetevents-api 2.13.0), which the plain `~/.m2` glob does not reach. Per the task
# brief, fall back to a hardcoded expected newest version below. BUMP EXPECTED_NEWEST once
# per release when packetevents learns a newer protocol. To re-derive it manually on a box
# with binutils, run:
#   unzip -p <packetevents-api-x.y.z.jar> \
#     com/github/retrooper/packetevents/protocol/player/ClientVersion.class \
#     | strings | grep -oE '^V_[0-9A-Z_]+$' | sort -V | tail -1 | sed 's/^V_//'
set -euo pipefail
EXPECTED_NEWEST="26_1"
grep -q "map(0x[0-9A-Fa-f]\+, MINECRAFT_${EXPECTED_NEWEST}, false)" velocity-patch/seamless.patch \
  || { echo "FAIL: seamless.patch has no decoded Respawn map for MINECRAFT_${EXPECTED_NEWEST}"; exit 1; }
echo "OK: StateRegistry table is fresh (MINECRAFT_${EXPECTED_NEWEST})."
