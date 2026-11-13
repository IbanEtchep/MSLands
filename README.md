# ArtisanLands

Territoires par tronçons (claims) pour Paper 1.21+ et Folia, add-on d'[Artisan](https://artisanmc.net).
Anciennement MSLands : le plugin se déclare `provides: [MSLands]` et garde le package `fr.iban.lands`,
donc Warps et MSBoosts tournent sans recompilation.

## Dépendances

- **Artisan** (API 1.0.0 au moins) : base de données, traductions, menus.
- **packetevents**.
- Optionnels : ArtisanGuilds (territoires de guilde), QuickShop-Hikari, Vault, HeadDatabase (têtes des
  menus), Core (seulement pour la copie des données de MSLands et le délai de téléportation).

## Construire

```bash
./gradlew build   # tests + build/libs/ArtisanLands-<version>.jar
```

`artisan-core-api` et `artisan-guilds` viennent de repo.artisanmc.net.

## Publier

`net.artisanmc:artisan-lands` sur repo.artisanmc.net (`.github/workflows/release.yml`, secrets
`ARTISAN_REPO_USERNAME` et `ARTISAN_REPO_PASSWORD`) :

- chaque push sur `main` publie le snapshot (`2.0.0-SNAPSHOT`) dans `/snapshots` ;
- un tag `v<version>` (`git tag -a v2.0.0 -m "notes"`) publie `<version>` dans `/releases` et met
  `ArtisanLands-<version>.jar` sur une Release GitHub brouillon, à publier à la main.

Pour compiler contre ArtisanLands (Warps, MSBoosts) :

```kotlin
repositories { maven("https://repo.artisanmc.net/releases") }
dependencies { compileOnly("net.artisanmc:artisan-lands:2.0.0") }
```

## Où sont les choses

| Quoi | Où |
|---|---|
| Tables | `artisan_lands_*` dans la base d'Artisan, versionnées (`storage/LandSchema`) |
| Copie des données de MSLands | `storage/LegacyImport`, une fois, au premier démarrage |
| Messages | `plugins/Artisan/modules/lands/lang/{fr,en}.yml` (modèle : `src/main/resources/lang/`) |
| Menus et dialogs | `plugins/Artisan/modules/lands/menus/`, `dialogs/`, éditables dans l'éditeur Artisan (modèle : `src/main/resources/artisan-system/lands/`) |
| Data sources et actions des menus | `menu/LandMenuData`, `menu/LandMenuActions`, déclarées dans `menu/LandsModule` |

Une modification d'un menu livré = monter `LandsModule.SYSTEM_VERSION`. Les noms et arguments des
data sources et actions `lands:*` sont publics (les menus d'un admin les utilisent) : on n'en renomme
aucun.

## Passer de MSLands à ArtisanLands

Le détail est dans le plan du projet (`architecture/plan-artisan-lands.md`, section 3). En bref :

1. `environment.yml` d'Artisan : `name` = le `servername` de Core (sinon `server-name:` dans
   `plugins/ArtisanLands/config.yml`).
2. Remplacer le jar MSLands par ArtisanLands, Core encore installé, ArtisanGuilds déjà en place.
3. Démarrer **un** serveur : il copie les tables `land_*` (lues seulement) et `plugins/MSLands/config.yml`.
   Le log donne les comptes copiés.
4. Démarrer les autres serveurs.

Retour arrière : remettre MSLands, ses tables `land_*` sont intactes.
