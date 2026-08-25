# Patch Velocity — switch seamless (zéro écran)

Patch du **cœur de Velocity** (branche `dev/3.0.0`, v3.6.0-SNAPSHOT) qui permet le changement de
serveur sans écran de chargement. Vue d'ensemble du système : voir le [README](../README_FR.md) à
la racine et [docs/EXPERIMENTAL_FR.md](../docs/EXPERIMENTAL_FR.md).

> 🇬🇧 English version: [README.md](README.md)

- `velocity-instanttransfer.jar` — jar proxy patché pré-compilé (remplace ton jar Velocity).
- `seamless.patch` — le diff source complet, pour relecture ou rebuild.

## Ce que fait le patch

1. **Skip de la phase config** — `ClientPlaySessionHandler.doSwitch()` : au switch (client ≥
   1.20.2), `switchToConfigState()` n'est plus appelé — le client **reste en PLAY**.
   `ConfigSessionHandler` absorbe la configuration du backend côté proxy : `KnownPacks` renvoyé en
   écho au backend, `RegistrySync`/`Tags`/plugin messages de config avalés, `FinishedUpdate`
   acquitté au backend sans toucher au client.
2. **Zéro écran** — `doSeamlessClientServerSwitch()` n'envoie **ni JoinGame ni Respawn** au client.
   Le monde se transforme sur place via les chunks + teleport du nouveau serveur. Le plugin proxy
   compagnon (InstantTransfer) gèle le joueur pendant le trou de chunk, nettoie les fantômes et
   échange les entity-ids ; l'agent backend couvre dégâts de chute et kicks de vol.
   Sur **1.13–1.20.1** (pas de phase CONFIGURATION du tout), `doSeamlessLegacySwitch()` fait la
   même chose purement en état PLAY : le JoinGame du backend est intercepté et jamais transmis,
   et la même annonce ids synthétique arme les plugins compagnons.
3. **Annonce ids/gamemode** — à chaque switch seamless, le patch écrit un plugin message
   synthétique (`instanttransfer:ids`, payload `clientId:backendId:gamemode`) que le plugin proxy
   intercepte (le client ne le voit jamais) pour piloter le remap d'entity-id et la resync du
   gamemode.

## Flags (propriété JVM ou variable d'environnement)

| Flag | Effet | Défaut |
|---|---|---|
| `-Dvelocity.seamlessSwitch=false` | TOUT désactiver (comportement Velocity vanilla) | seamless **ON** |
| `-Dvelocity.seamlessRespawn=true` | Repli robuste : Respawn même-dimension KEEP_ALL_DATA (bref flash « loading terrain », mais reset client complet et propre, sans plugins compagnons) | **OFF** (= zéro écran) |

**Maintenance — nouvelle version de Minecraft :** ajoute la ligne `map(...)` correspondante à la
table de décodage Respawn dans `seamless.patch` (`StateRegistry`), lance
`scripts/check-protocol-tables.sh` + `scripts/validate-patch-hunks.sh`, puis rebuild le jar
(voir [Rebuild depuis les sources](#rebuild-depuis-les-sources)).

## Installation

1. Remplace ton jar Velocity par `velocity-instanttransfer.jar` (sauvegarde l'ancien).
2. `plugins/` du proxy : `packetevents-velocity` + le plugin proxy InstantTransfer
   (avec `seamless-enabled=true` dans `plugins/instanttransfer/config.properties`).
3. `plugins/` de chaque backend : `packetevents-spigot` + le plugin InstantTransferAgent.
4. Aucun flag nécessaire (le zéro-écran est le défaut).

## Logs à surveiller pendant un switch

- Cœur proxy : `[seamless] doSwitch ... seamless=true` puis
  `[seamless] doSeamlessClientServerSwitch: zero-screen ...`
- Plugin proxy : `[InstantTransfer] switch armed ...`, `entity-id remap ... (swap active)`,
  `arrival teleport ... freeze on`, puis
  `seamless arrival for X (arrival chunk delivered): removed N ghost entities, M ghost chunks, K ghost effects`
- Backend (agent `debug=true`) : `[fall-guard] arrival grace armed ...`, `[COORD] received ... unfreeze`

## Si ça casse

- `-Dvelocity.seamlessRespawn=true` → repli propre avec bref flash (garde le skip config).
- `-Dvelocity.seamlessSwitch=false` → comportement Velocity vanilla complet.

## Rebuild depuis les sources

```
git clone https://github.com/PaperMC/Velocity && cd Velocity
git fetch origin dev/3.0.0 && git checkout FETCH_HEAD
git apply /chemin/vers/seamless.patch
./gradlew :velocity-proxy:shadowJar
# -> proxy/build/libs/velocity-proxy-3.6.0-SNAPSHOT-all.jar
```
