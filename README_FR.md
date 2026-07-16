# InstantTransfer

**Changement de serveur sans écran de chargement** pour réseaux Minecraft (Velocity + Paper).

Quand un joueur passe d'un backend à un autre derrière un proxy, Minecraft affiche normalement
l'écran « Downloading terrain » (et sur 1.20.2+, un rechargement complet du monde via la phase
CONFIGURATION). InstantTransfer supprime tout ça : le monde *se transforme sur place* — pas
d'écran, pas de déconnexion, aucune transition visible.

> 🇬🇧 English version: [README.md](README.md)

## 🎬 Démo

**[▶️ Voir la vidéo de démo](docs/InstantTransfer-demo.mp4)** — un switch en direct entre deux
backends : aucun écran de chargement, le monde se transforme simplement autour du joueur.

## ⚠️ Expérimental — à NE PAS mettre en production

Ce projet patche les **internes de Velocity** et réécrit le trafic protocole en direct. C'est un
proof of concept fonctionnel, testé sur un petit réseau 1.21.x — pas un produit durci. Attendez-vous
à de la casse avec : des versions Minecraft non testées, des mods, d'autres plugins packet-level,
les changements de dimension, ou des registres/datapacks différents entre backends.
**À utiliser sur des réseaux de test, à vos risques et périls.**

## Fonctionnement

Trois composants qui coopèrent (les trois sont nécessaires) :

| Composant | Où | Rôle |
|---|---|---|
| `velocity-patch/` | remplace le jar Velocity | Saute la phase CONFIGURATION client au switch (la config du backend est absorbée côté proxy via un écho known-packs) et n'envoie **ni JoinGame ni Respawn** — le client ne quitte jamais l'état PLAY. Annonce les paires d'entity-id au plugin. |
| `proxy-plugin/` | `plugins/` du proxy (+ [packetevents-velocity](https://github.com/retrooper/packetevents)) | Trace ce que le client voit (chunks, entités, effets, abilities). À l'arrivée : laisse passer le teleport, **gèle le joueur en vol client-side** jusqu'à la livraison du chunk d'arrivée (pas de traversée du sol), puis supprime les fantômes de l'ancien serveur (entités, chunks, effets de potion) et **échange les entity-ids** (issue Velocity #251 : niveau OP / F3+F4, knockback, effets, attributs…). |
| `backend-agent/` | `plugins/` de chaque backend (+ [packetevents-spigot](https://github.com/retrooper/packetevents)) | Grâce d'arrivée : annule les dégâts de chute, autorise temporairement le vol côté serveur (le gel ne peut jamais kick), et le révoque dès que le proxy signale la fin de l'arrivée (canal plugin-message). |

Conditions pour un switch seamless : backends sur la **même version de Minecraft** avec des
**registres/datapacks identiques**, et (pour l'instant) la **même dimension**.

## Installation

1. Build (JDK 21, Maven + Gradle) :
   ```
   mvn -f pom.xml clean package                # proxy-plugin + backend-agent
   # Patch Velocity : voir velocity-patch/README.md (jar pré-compilé inclus)
   ```
2. Remplace ton jar Velocity par le jar patché (`velocity-patch/`).
3. `plugins/` du proxy : `packetevents-velocity` + `proxy-plugin/target/InstantTransfer-*.jar`.
4. `plugins/` de chaque backend : `packetevents-spigot` + `backend-agent/target/InstantTransferAgent-*.jar`.
5. Change de serveur. Il ne doit y avoir aucun écran.

### Flags / config

| Quoi | Où | Défaut |
|---|---|---|
| Tout désactiver (comportement Velocity vanilla) | `-Dvelocity.seamlessSwitch=false` | activé |
| Mode repli : Respawn même-dimension propre (bref flash, sans plugins compagnons) | `-Dvelocity.seamlessRespawn=true` | off (= zéro-écran) |
| Coupe-circuit du plugin proxy, logs debug | `plugins/instanttransfer/config.properties` | activé |
| Logs verbeux de l'agent backend | `plugins/InstantTransferAgent/config.yml` → `debug` | true |

## Structure du dépôt

```
proxy-plugin/             Plugin Velocity (packetevents) : tracking, gel d'arrivée, nettoyage fantômes, remap id
backend-agent/            Plugin Paper : grâce d'arrivée (garde chute/vol), canal de coordination
velocity-patch/           Jar Velocity patché (velocity-instanttransfer.jar) + diff source + instructions
docs/EXPERIMENTAL_FR.md   Journal de R&D : approches essayées, échecs et causes, audit de couverture, roadmap
```

## État & limites connues

- ✅ Zéro écran, pas de dégâts de chute, pas d'entités/chunks/effets fantômes, remap entity-id —
  validé en live sur 1.21.x (Velocity dev/3.0.0, Paper 1.21.4).
- ⚠️ Switchs même-dimension uniquement ; registres identiques requis (datapacks différents → ne pas utiliser).
- ⚠️ Pas encore resynchronisé : cas limites météo/heure, scoreboard/bossbar/tab-list, gamemodes différents entre backends.
- ⚠️ Un joueur qui bouge pendant le handshake (~300 ms) peut recevoir une correction de position bénigne (`moved too quickly` dans les logs).

Inspiré d'une implémentation privée montrée par Lodjo28 (code non publié) ; ceci est une
réimplémentation indépendante à partir de zéro.
