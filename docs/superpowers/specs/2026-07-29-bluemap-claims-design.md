# BlueMap Claims Integration Design

## Goal

Add an optional BlueMap integration that displays MSLands chunk claims on the
web map and keeps them synchronized with claim and unclaim operations.

## Scope

The integration displays:

- player lands;
- guild lands;
- system lands.

Sublands are not displayed in this first version. Marker details contain only
the land name, land type, and owner identity. Player lands use the player's
name, guild lands use the guild's name, and system lands have no owner.

## Architecture

BlueMap remains an optional dependency. MSLands declares BlueMap as a
`softdepend`, compiles against BlueMap API `2.7.7`, and only creates the
integration when BlueMap is installed and enabled. BlueMap-specific types stay
isolated in an integration package so MSLands can load normally when BlueMap is
absent.

The integration registers BlueMap lifecycle callbacks:

- on BlueMap enable, it creates one MSLands marker set on every applicable
  BlueMap map and performs a full rebuild from `LandRepository`;
- on BlueMap disable, it discards its references to BlueMap maps and marker
  sets;
- when BlueMap reloads, its enable callback performs the same full rebuild.

Only chunks whose `server` equals `LandsPlugin#getServerName()` are eligible.
A chunk is added only to BlueMap maps representing its Bukkit world.

## Marker Model

Each claimed chunk is represented by one BlueMap `ShapeMarker` at Y coordinate
`64`. The marker is a rectangle covering the exact block area from
`(chunkX * 16, chunkZ * 16)` to `(chunkX * 16 + 16, chunkZ * 16 + 16)`. Using
the exclusive upper boundary prevents one-block gaps and overlaps between
neighboring chunks.

Marker identifiers use `chunk:<chunk-x>:<chunk-z>`, which is deterministic and
collision-free within a map because only one land can own a chunk. This allows
a single marker to be replaced or removed using only the event's chunk data,
without rebuilding unrelated claims. The marker-set identifier is
`mslands-claims`.

All markers belong to one toggleable marker set labelled `Territoires`. The set
is visible by default. Player, guild, and system lands each use a separately
configurable line color, fill color, fill opacity, and a shared line width.

The marker label is the land name. Its detail panel contains:

- the land name;
- the localized type (`Joueur`, `Guilde`, or `Système`);
- `Propriétaire: <player name>` for player lands, falling back to the owner UUID
  when Bukkit has no cached player name;
- `Guilde: <guild name>` for guild lands, falling back to the guild UUID when
  the guild name is unavailable;
- no owner row for system lands.

Dynamic text is HTML-escaped before being passed to BlueMap.

## Synchronization

The initial full rebuild reads the repository's cached chunk-to-land map and
does not load Bukkit chunks.

`PlayerChunkClaimEvent` and `PlayerChunkUnclaimEvent` are emitted before the
repository mutation completes. The BlueMap listener therefore schedules a
one-tick-later synchronization for the affected `SChunk`, following the
existing `SeeClaims` update pattern. At execution time it reads the final state
from `LandRepository`:

- if the chunk belongs to a supported land, the marker is inserted or replaced;
- if the chunk is wilderness, the marker is removed.

This makes synchronization respect cancellations and other listeners that
affect the operation. A claim in a world with no BlueMap map is ignored.

Land renames are persisted through `LandService#renameLand`. After a successful
rename, the service emits a BlueMap-independent `PlayerLandRenameEvent`
containing the renamed land. The integration listens to this event and refreshes
every marker belonging to that land so labels and details remain current.

## Configuration

`config.yml` gains a `bluemap` section with:

- `enabled`, defaulting to `true`;
- marker-set label `Territoires` and `default-hidden: false`;
- `line-width: 2`;
- player line and fill color `#3498DB`, with fill opacity `0.25`;
- guild line and fill color `#2ECC71`, with fill opacity `0.25`;
- system line and fill color `#E74C3C`, with fill opacity `0.25`.

Invalid colors or numeric values do not prevent MSLands from enabling. The
accepted color format is `#RRGGBB`, fill opacity is clamped to the range
`0.0`–`1.0`, and line width must be a positive integer. The integration logs
one warning per invalid setting and falls back to the default listed above.

## Failure Handling

If BlueMap is absent or the integration is disabled in configuration, MSLands
starts without registering any BlueMap callback.

If BlueMap is present but a map cannot be matched to a Bukkit world, that map is
skipped. Failure to create or update one marker is logged with the land and
chunk identifiers and does not stop synchronization of other markers.

All BlueMap API access occurs through the lifecycle callbacks or while an active
API instance is retained. No marker state is persisted to disk because BlueMap
markers are recreated from the authoritative MSLands repository on each load.

## Testing

The project gains a JUnit 5 test setup. BlueMap-facing logic is split so the
deterministic parts can be tested without starting a Minecraft server.

Unit tests cover:

- chunk rectangles, including positive and negative coordinates;
- deterministic marker identifiers and uniqueness across positive and negative
  chunk-coordinate pairs;
- filtering by current server and map world;
- mapping each supported land type to its configured style;
- escaped marker details for player, guild, and system lands;
- insertion/replacement for a claimed chunk and removal for wilderness;
- refreshing all markers after a land rename;
- invalid configuration values falling back to defaults.

The Gradle build and complete test suite must pass with the BlueMap dependency
declared as compile-only.

## Out of Scope

- merging adjacent chunks into polygon contours;
- displaying sublands;
- displaying trusts, members, bans, flags, or permissions;
- persisting marker files;
- adding commands or a live configuration reload command.
