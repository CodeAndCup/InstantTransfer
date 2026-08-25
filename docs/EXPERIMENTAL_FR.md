# EXPERIMENTAL — notes de R&D & justification du design

> 🇬🇧 English version: [EXPERIMENTAL.md](EXPERIMENTAL.md)

**Tout ce projet est expérimental.** Il patche les internes de Velocity et réécrit le trafic
protocole en direct. Il n'a été validé que sur un petit réseau de test 1.21.x — à ne **pas**
utiliser en production. Ce document est le journal de R&D du projet : ce qui a été essayé, ce qui
a échoué et pourquoi, comment le design actuel fonctionne, et ce qui reste.

Objectif : faire passer un joueur d'un backend à un autre avec **zéro transition visible** — pas
d'écran « Downloading terrain », pas de rechargement du monde, pas de déconnexion.
Cible : MC 1.21.x, Velocity + Paper.

## 1. Ce qui est ACQUIS (verrous levés — ne plus y toucher)

| Acquis | Comment | Où |
|---|---|---|
| **Phase CONFIGURATION éliminée** (cause n°1 de l'écran de switch sur 1.20.2+) | Patch Velocity : le client ne quitte jamais PLAY ; la config du backend est absorbée côté proxy (écho known-packs, registres avalés) | `velocity-patch/` |
| **Pas de déconnexion** pendant le switch | Confirmé en tests live | — |
| **Nettoyage des fantômes** (mobs/chunks/effets de potion de l'ancien serveur) | Tracking packetevents côté proxy + DestroyEntities/UnloadChunk/RemoveEntityEffect + reset des metadata de particules à l'arrivée | plugin proxy |
| **Pas de dégâts de chute / traversée du sol** à l'arrivée | Gel en vol (proxy) + fenêtre de grâce (agent) | plugin proxy + agent |
| **Remap d'entity-id** (issue Velocity #251 : niveau OP F3+F4, knockback, effets, attributs) | Message ids synthétique du patch + **rewrite directionnel** dans le plugin (backendId→clientId serveur→client, clientId→backendId client→serveur) | patch + plugin proxy |
| **Resync du gamemode** (cassait la bouffe : mastication infinie) | Gamemode transporté dans le message ids + ChangeGameState à l'arrivée | patch + plugin proxy |
| **Fallback cross-dimension** (zéro-écran unsafe si dim différente) | Patch trace la dim client ; fallback auto vers Respawn KEEP_ALL_DATA si dim backend ≠ dim client | `velocity-patch/` |
| **Fix rollback timeout** (garde 3 s causait rollback) | Au timeout : envoie correction position aux coords absolues d'arrivée + garde le vol ; étend la garde. Ne marche que pour téléports absolus. | plugin proxy |

## 2. Méthodes TESTÉES et REJETÉES (ne pas re-tenter)

| Méthode | Verdict | Cause racine |
|---|---|---|
| Annuler le JoinGame en plugin packetevents pur (v0.1) | ❌ Déconnexion | Le client était déjà passé en CONFIGURATION ; un plugin proxy ne voit que la connexion client et ne peut pas supprimer le handshake config à 3 (client↔Velocity↔backend) |
| « Faux instantané » (préchargement seul, sans patch) | ❌ Écran toujours là | L'écran vient de la transition PLAY→CONFIG→PLAY elle-même, pas de l'attente des chunks |
| Transfer packets (TransferProxy) | ❌ Piège | C'est une reconnexion complète → écran garanti |
| Respawn même-dimension (keptData=0 puis 0x03) | ❌ Flash ~0,5 s | Le Respawn LUI-MÊME déclenche `ReceivingLevelScreen` quel que soit keptData — c'est aussi lui qui fait attendre le chunk d'arrivée (écran et anti-traversée = même mécanisme vanilla) |
| No-Respawn brut (rien envoyé, sans accompagnement) | ❌ Zéro écran MAIS dégâts de chute + mobs fantômes + traversée du sol | Sans Respawn, rien ne reset la physique ni ne démonte l'ancien monde |
| **Position-hold** (annuler le teleport d'arrivée, le rejouer à l'arrivée du chunk) | ❌ Spam infini + placement cassé | 4 défauts structurels : (1) `user.sendPacket()` re-déclenche le listener de façon réentrante → récursion infinie au timeout ; (2) annuler le teleport casse le handshake teleport-confirm de Paper ; (3) les chunks de l'ancien serveur restés dans le tracker faussaient le test « chunk déjà chargé » quand les spawns se recouvrent ; (4) coordonnées relatives non gérées |

## 3. Architecture ACTUELLE (3 composants)

```
[Velocity patché]  velocity-patch/velocity-instanttransfer.jar
   ├─ skip config (le client reste en PLAY)              ← défaut ON
   ├─ zéro écran : n'envoie NI JoinGame NI Respawn       ← défaut ON
   │  (repli : -Dvelocity.seamlessRespawn=true → Respawn propre, bref flash)
   └─ annonce "clientId:backendId:gamemode" au plugin (plugin message synthétique)

[Plugin proxy]  proxy-plugin/  (dans plugins/ du proxy, + packetevents-velocity)
   ├─ trace l'état visible du client : entités, chunks, effets de potion self, abilities
   ├─ à l'arrivée : le teleport PASSE (handshake intact) + GEL EN VOL injecté
   │  (le client flotte sur place → pas de chute ni de traversée pendant le chargement)
   ├─ quand le chunk d'arrivée arrive (ou timeout) : restaure les vraies abilities,
   │  supprime les fantômes (entités/chunks/effets + metadata de particules), resync le
   │  gamemode, signale à l'agent de terminer sa grâce (canal coord)
   ├─ **rewrite directionnel d'entity-id** : serveur→client réécrit backendId→clientId ;
   │  client→serveur réécrit clientId→backendId (pas de swap sur les autres entités)
   └─ **garde timeout** : configurable (défaut 5 s) ; au timeout envoie correction position
      aux coords absolues d'arrivée + garde le vol ; étend la garde jusqu'à l'arrivée du chunk

[Agent backend]  backend-agent/  (sur CHAQUE backend, + packetevents-spigot)
   ├─ fall-guard : annule les dégâts FALL/SUFFOCATION pendant une grâce de **1 s** (était 3 s)
   └─ allowFlight temporaire pendant la grâce (sinon le gel ferait kick « Flying is
      not enabled »), révoqué instantanément au message unfreeze du proxy
```

### Pourquoi le gel en vol plutôt que le position-hold
Pour une arrivée à position **arbitraire** (dernière position connue, potentiellement vieille de
plusieurs mois), l'attente du chunk d'arrivée est incontournable. Le position-hold cachait
l'attente en retenant le teleport — mais retenir le teleport casse le protocole. Le gel en vol
cache la même attente **sans rien retenir** : tous les paquets circulent normalement ; on ajoute
seulement un état client temporaire (le vol) qui neutralise la gravité. C'est le mécanisme le
moins invasif possible.

## 4. Flags & config

| Quoi | Où | Défaut |
|---|---|---|
| Skip config | `-Dvelocity.seamlessSwitch=false` pour désactiver | ON |
| Mode Respawn (repli robuste, bref flash) | `-Dvelocity.seamlessRespawn=true` | OFF (= zéro écran) |
| Coupe-circuit / debug du plugin proxy | `plugins/instanttransfer/config.properties` | activé |
| **Timeout garde (ms)** | `plugins/instanttransfer/config.properties` → `guard-timeout-ms` | **5000** |
| Logs verbeux de l'agent ([DIAG-BACKEND], [COORD], [fall-guard]) | `plugins/InstantTransferAgent/config.yml` → `debug` | false |

## 5. Audit de couverture — « tout ce que JoinGame/Respawn resetait pour nous »

| État client | Couvert par | Statut |
|---|---|---|
| Chunks + entités de l'ancien monde | ghost cleanup (plugin) | ✅ |
| Entity-id du joueur | swap d'id (patch + plugin) | ✅ |
| Effets de potion (icônes HUD + metadata de particules) | ghost effects + reset metadata 10/11 | ✅ |
| Distance de chute / vélocité | gel en vol + fall-guard agent | ✅ |
| Abilities (vol, vitesse, FOV) | trackées & rejouées à la release | ✅ |
| Gamemode | ChangeGameState(3) à la release, transporté dans le message ids | ✅ (c'était le bug de la bouffe infinie) |
| Vie / faim / XP / slot main / inventaire | renvoyés par le nouveau serveur à son 1er tick (dirty-sync) | ✅ automatique |
| Tab-list, boss bars | gérés par Velocity au switch | ✅ automatique |
| View/simulation distance, recettes, advancements | renvoyés au join | ✅ automatique |
| Cooldowns d'items (ender pearl, bouclier…) | tracking SET_COOLDOWN → mis à zéro à la release (un vrai join ne les transporte jamais) | ✅ |
| Scoreboard/objectifs/équipes de l'ancien serveur | tracking SCOREBOARD_OBJECTIVE/TEAMS → REMOVE à la release ; un re-CREATE d'un nom encore fantôme reçoit d'abord un REMOVE injecté (le client vanilla JETTE une exception sur un CREATE d'objectif dupliqué → kick « network error ») ; le fallback Respawn les nettoie aussi (le scoreboard est la seule chose qui survit à un respawn côté client) | ✅ |
| Météo fantôme (pluie de l'ancien serveur) | tracking météo CHANGE_GAME_STATE → END_RAINING + niveaux pluie/orage à zéro à la release si le nouveau serveur n'a rien envoyé | ✅ |
| GUI/coffre ouvert pendant le switch | tracking OPEN_WINDOW/OPEN_HORSE_WINDOW → CloseWindow à la release | ✅ |
| Entrée tab-list du joueur lui-même (le client met en cache son objet PlayerInfo dans l'entité joueur ; un cycle remove/re-add orpheline le cache → le noclip spectateur casse, isSpectator/isCreative lisent un gamemode périmé) | UUID du joueur filtré des PLAYER_INFO_REMOVE + re-ADD rétrogradés en updates sur l'entrée survivante | ✅ |
| Dimension différente entre serveurs | le patch compare le type de dimension à chaque switch → fallback automatique en vrai Respawn (reload vanilla correct, bref écran) ; le plugin détecte le Respawn et met sa mécanique en veille ; les Respawn relayés des backends (portails, /execute in … tp, mort) mettent aussi à jour la dimension trackée | ✅ (fallback, pas zéro-écran : un level client ne peut pas changer de ciel/hauteur sur place) |
| Backends antérieurs à 1.20.2 (pas de phase CONFIGURATION) | switch en état PLAY dans le patch : JoinGame du backend intercepté, jamais transmis (voir §7) ; Respawn décodable sur toutes les versions via la table `map(...)` du StateRegistry | ✅ |
| Swirls d'effets fantômes sur les versions aux indices de metadata non vérifiés (< 1.20.2) | REMOVE_ENTITY_EFFECT envoyé quand même (l'icône HUD est nettoyée) ; le reset des metadata de swirl est proprement ignoré plutôt que de deviner des indices historiques — les swirls restants disparaissent à l'expiration naturelle de l'effet | ✅ (dégradation acceptée) |

## 6. Travail restant

1. Plage de versions élargie : fait sur cette branche — la plage supportée est désormais MC
   **1.13 → dernière** (voir §7) ; prochaine étape : validation live de cette matrice, puis BungeeCord.
2. Note de déploiement : chaque switch renvoie toute la view distance du joueur (~400 paquets de
   chunks) ; dimensionner le heap des backends en conséquence (≥ 2 Go recommandé) — un heap trop
   petit se manifeste par des stalls GC (« Can't keep up! ») puis un OutOfMemoryError pendant les
   sauvegardes de chunks.

## 7. Support multi-versions & passe de performances (2026-08-25)

### Un design, deux ères de protocole
Le switch zéro-écran fonctionne désormais sur tout réseau homogène de MC **1.13 à la dernière
version**, via deux chemins derrière le même `doSwitch()` :
- **≥ 1.20.2 — absorption de la phase config** : le backend envoie sa phase CONFIGURATION et le
  patch l'absorbe entièrement côté proxy (KnownPacks renvoyé en écho, RegistrySync/Tags avalés) ;
  le client ne quitte jamais PLAY.
- **1.13 – 1.20.1 — switch en état PLAY** : ces versions n'ont pas de phase configuration du tout,
  donc il n'y a rien à absorber — le patch intercepte simplement le JoinGame du backend dans
  `doSeamlessLegacySwitch` et ne le transmet jamais. Strictement *plus simple* que le chemin
  ≥ 1.20.2 : pas de handshake à 3 (client↔Velocity↔backend) à chorégraphier, pas d'écho known-packs.
Les deux chemins s'appuient sur la table de décodage Respawn (`StateRegistry`, une ligne
`map(...)` par version) pour que le tracking de dimension client — et avec lui le fallback
cross-dimension vers Respawn — marche sur toutes les ères. Les plugins compagnons suivent :
l'agent est compilé en bytecode Java 8 / `api-version: 1.13` pour se charger sur les serveurs
les plus vieux supportés.

### Dégradation gracieuse pour les metadata des ères non vérifiées
Règle : ne jamais deviner des indices de metadata historiques. Sur les ères dont les indices
effect-color/particles des entités vivantes n'ont pas été vérifiés (< 1.20.2), le reset des
metadata de swirl est sauté ; REMOVE_ENTITY_EFFECT reste envoyé, donc l'effet HUD est toujours
nettoyé — seul le swirl cosmétique peut subsister jusqu'à l'expiration naturelle de l'effet.
Dégradation acceptée, jamais de corruption.

### Passe de performances
Deltas qualitatifs livrés sur cette branche :
- lectures brutes des coordonnées de chunks au lieu de lookups boxed sur les chemins chauds ;
- peek des paquets de mouvement au lieu d'un décodage complet là où seul le mouvement compte ;
- unloads de chunks fantômes différés, dédoublonnés avec les resends de la destination (un chunk
  que le nouveau serveur renvoie n'est plus déchargé puis immédiatement re-livré) ;
- mémoire de tracking des chunks chargés bornée (tracking élagué aux téléports lointains).

### Résultats mesurés
(en attente — rempli par le run matrice de la Task 12)

### Contrat de maintenance par release
Supporter une nouvelle version de Minecraft coûte une ligne : ajoute l'entrée `map(...)`
correspondante à la table de décodage Respawn dans `velocity-patch/seamless.patch`
(`StateRegistry`), lance `scripts/check-protocol-tables.sh` (échoue si la table prend du retard
sur la version la plus récente connue de packetevents) + `scripts/validate-patch-hunks.sh`
(vérifie les compteurs des hunks du patch), puis rebuild le jar selon
[velocity-patch/README_FR.md](../velocity-patch/README_FR.md).
