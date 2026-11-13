package fr.iban.lands.menu;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.ActionGroup;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.enums.LinkType;
import fr.iban.lands.guild.AbstractGuildDataAccess;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.Trust;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.SubLand;
import fr.iban.lands.utils.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rows of the land menus. Each fetcher runs on the viewer's thread at every render, so it reads
 * memory only; the chunk counts, which live in the database, come from a cache filled off-thread
 * ({@link #refreshLimits}). A null viewer is the editor's preview: it gets sample rows.
 *
 * <p>A land is named by its id, and every fetcher returns nothing unless the viewer may manage that
 * land ({@link #canManage}): Artisan does not check a menu's open requirements, and an admin may edit
 * the menus. The actions check again ({@link LandMenuActions}).
 */
public final class LandMenuData {

    private static final String SAMPLE_ID = "00000000-0000-0000-0000-000000000001";

    private final LandsPlugin plugin;
    private final LandRepository lands;
    private final LandMessages messages;
    /** Chunks used and allowed, per owner (player or guild), as last read from the database. */
    private final Map<UUID, Limits> limits = new ConcurrentHashMap<>();
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();

    public record Limits(int used, int max, long readAt) {}

    public LandMenuData(LandsPlugin plugin) {
        this.plugin = plugin;
        this.lands = plugin.getLandRepository();
        this.messages = plugin.getMessages();
    }

    // ---------------------------------------------------------------- access

    /** Whether {@code viewer} may manage {@code land}: its owner, its guild's managers, a sub-land's parent's, or an admin. */
    public boolean canManage(Player viewer, @Nullable Land land) {
        if (land == null || lands.isWilderness(land)) return false;
        if (viewer.hasPermission("lands.admin") || plugin.isBypassing(viewer)) return true;
        if (land instanceof SubLand subLand) {
            return subLand.getSuperLand() != null && canManage(viewer, subLand.getSuperLand());
        }
        return lands.canManageLand(viewer, land);
    }

    /** Whether {@code viewer} may list and create the lands of {@code scope} for {@code owner}. */
    public boolean canOpen(Player viewer, LandMenus.Scope scope, @Nullable UUID owner, @Nullable Land parent) {
        boolean admin = viewer.hasPermission("lands.admin");
        return switch (scope) {
            case PLAYER -> owner != null && (admin || owner.equals(viewer.getUniqueId()));
            case GUILD -> {
                if (owner == null) yield false;
                if (admin) yield true;
                AbstractGuildDataAccess guilds = plugin.getGuildDataAccess();
                yield plugin.isGuildsHookEnabled() && guilds != null
                        && owner.equals(guilds.getGuildId(viewer.getUniqueId()))
                        && guilds.canManageGuildLand(viewer.getUniqueId());
            }
            case SYSTEM -> admin;
            case SUBLANDS -> parent != null && !(parent instanceof SubLand)
                    && viewer.hasPermission("lands.sublands") && canManage(viewer, parent);
        };
    }

    /** The land {@code params.land} when the viewer may manage it, else null. */
    @Nullable Land managed(Player viewer, Map<String, Object> params) {
        Land land = land(param(params, "land"));
        return canManage(viewer, land) ? land : null;
    }

    @Nullable Land land(String id) {
        UUID uuid = uuid(id);
        return uuid == null ? null : lands.getLandById(uuid).orElse(null);
    }

    // ---------------------------------------------------------------- lists of lands

    /** {@code scope}, {@code owner}, {@code parent}: the lands of the main menu. */
    public List<Map<String, Object>> lands(@Nullable Player viewer, Map<String, Object> params) {
        if (viewer == null) {
            return List.of(landRow(sampleLand("Maison")), landRow(sampleLand("Ferme")));
        }
        LandMenus.Scope scope = LandMenus.Scope.parse(param(params, "scope"));
        if (scope == null) return List.of();
        UUID owner = uuid(param(params, "owner"));
        Land parent = land(param(params, "parent"));
        if (!canOpen(viewer, scope, owner, parent)) return List.of();
        return of(scope, owner, parent).stream()
                .sorted(Comparator.comparing(Land::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::landRow)
                .toList();
    }

    Collection<? extends Land> of(LandMenus.Scope scope, @Nullable UUID owner, @Nullable Land parent) {
        return switch (scope) {
            case PLAYER -> lands.getLands(owner, LandType.PLAYER);
            case GUILD -> lands.getGuildLands(owner);
            case SYSTEM -> lands.getSystemLands().stream().filter(land -> !lands.isWilderness(land)).toList();
            case SUBLANDS -> parent == null ? List.of() : List.copyOf(parent.getSubLands().values());
        };
    }

    private Map<String, Object> landRow(Land land) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", land.getId().toString());
        row.put("name", land.getName());
        row.put("type", land.getType().name().toLowerCase(Locale.ROOT));
        row.put("chunks", land instanceof SubLand ? 0 : lands.getChunks(land).size());
        row.put("trusts", land.getTrusts().size());
        row.put("bans", land.getBans().size());
        return row;
    }

    /** {@code land}: one row, the land a menu manages. */
    public List<Map<String, Object>> land(@Nullable Player viewer, Map<String, Object> params) {
        Land land = viewer == null ? sampleLand("Maison") : managed(viewer, params);
        if (land == null) return List.of();
        Map<String, Object> row = landRow(land);
        row.put("subland", land instanceof SubLand);
        row.put("can_sublands", !(land instanceof SubLand)
                && (viewer == null || viewer.hasPermission("lands.sublands")));
        row.put("guild_trust", viewer == null || guildTrustAvailable(viewer, land));
        row.put("sublands", land.getSubLands().size());
        String area = "";
        String areaServer = "";
        boolean areaSet = false;
        if (land instanceof SubLand subLand) {
            Cuboid cuboid = subLand.getCuboid();
            areaServer = subLand.getServer() == null ? "" : subLand.getServer();
            if (cuboid != null) {
                areaSet = true;
                area = cuboid.getWorld().getName() + " " + cuboid.getLowerX() + " " + cuboid.getLowerY() + " "
                        + cuboid.getLowerZ() + " → " + cuboid.getUpperX() + " " + cuboid.getUpperY() + " " + cuboid.getUpperZ();
            }
        }
        row.put("area_set", areaSet);
        row.put("area", area);
        row.put("area_server", areaServer);
        row.put("area_elsewhere", !areaSet && !areaServer.isEmpty());
        return List.of(row);
    }

    /** The guild members' permissions, in the trusts menu, for a land owned by someone in a guild. */
    private boolean guildTrustAvailable(Player viewer, Land land) {
        AbstractGuildDataAccess guilds = plugin.getGuildDataAccess();
        return plugin.isGuildsHookEnabled() && guilds != null && land.getOwner() != null
                && guilds.hasGuild(viewer.getUniqueId());
    }

    // ---------------------------------------------------------------- limits

    /** {@code scope}, {@code owner}: one row with the chunks used and allowed, none for system lands and sub-lands. */
    public List<Map<String, Object>> limits(@Nullable Player viewer, Map<String, Object> params) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (viewer == null) {
            row.put("used", "12");
            row.put("max", "50");
            row.put("guild", false);
            return List.of(row);
        }
        LandMenus.Scope scope = LandMenus.Scope.parse(param(params, "scope"));
        UUID owner = uuid(param(params, "owner"));
        if (scope != LandMenus.Scope.PLAYER && scope != LandMenus.Scope.GUILD) return List.of();
        if (!canOpen(viewer, scope, owner, null)) return List.of();
        Limits cached = cachedLimits(owner);
        row.put("used", cached == null ? "…" : String.valueOf(cached.used()));
        row.put("max", cached == null ? "…" : String.valueOf(cached.max()));
        row.put("guild", scope == LandMenus.Scope.GUILD);
        return List.of(row);
    }

    /**
     * {@code owner}'s chunk counts as last read, null before the first read: older than 10 seconds,
     * they are read again in the background. Never reads the database on the calling thread.
     */
    public @Nullable Limits cachedLimits(UUID owner) {
        Limits cached = limits.get(owner);
        // One read at a time per owner: a scoreboard asks at every refresh.
        if ((cached == null || System.currentTimeMillis() - cached.readAt() > 10_000) && refreshing.add(owner)) {
            refreshLimits(owner, () -> refreshing.remove(owner));
        }
        return cached;
    }

    /** Reads {@code owner}'s chunk counts on the database thread, then runs {@code then} (any thread). */
    void refreshLimits(@Nullable UUID owner, @Nullable Runnable then) {
        if (owner == null) {
            if (then != null) then.run();
            return;
        }
        plugin.runAsyncQueued(() -> {
            try {
                limits.put(owner, new Limits(lands.getChunkCount(owner), lands.getMaxChunkCount(owner),
                        System.currentTimeMillis()));
            } finally {
                if (then != null) then.run();
            }
        });
    }

    // ---------------------------------------------------------------- trusts

    /** {@code land}: the players with permissions on it. */
    public List<Map<String, Object>> trusts(@Nullable Player viewer, Map<String, Object> params) {
        if (viewer == null) return List.of(playerRow(UUID.fromString(SAMPLE_ID), "Steve"));
        Land land = managed(viewer, params);
        if (land == null) return List.of();
        return land.getTrusts().keySet().stream()
                .map(uuid -> playerRow(uuid, name(uuid)))
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** {@code land}, {@code target}: whose permissions the edit menu shows, as one row. */
    public List<Map<String, Object>> trustTarget(@Nullable Player viewer, Map<String, Object> params) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (viewer == null) {
            row.put("kind", "player");
            row.put("name", "Steve");
            return List.of(row);
        }
        if (managed(viewer, params) == null) return List.of();
        String target = param(params, "target");
        UUID uuid = uuid(target);
        String kind = uuid != null ? "player" : target.toLowerCase(Locale.ROOT);
        if (!kind.equals("player") && !kind.equals("global") && !kind.equals("guild")) return List.of();
        row.put("kind", kind);
        row.put("name", uuid != null ? name(uuid) : "");
        return List.of(row);
    }

    /**
     * {@code land}, {@code target} ({@code global}, {@code guild} or a player's UUID), {@code group}:
     * without a group, {@link Action#ALL} (alone when granted), then the groups, then the actions of
     * no group, as MSLands showed them; with one, that group's actions.
     */
    public List<Map<String, Object>> permissions(@Nullable Player viewer, Map<String, Object> params) {
        Trust trust;
        if (viewer == null) {
            trust = new Trust();
            trust.addPermission(Action.BLOCK_BREAK);
        } else {
            Land land = managed(viewer, params);
            trust = land == null ? null : trust(land, param(params, "target"));
        }
        if (trust == null) return List.of();

        ActionGroup group = enumOf(ActionGroup.class, param(params, "group"));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (group == null) {
            rows.add(actionRow(viewer, Action.ALL, trust));
            if (trust.hasPermission(Action.ALL)) return rows;
            for (ActionGroup g : ActionGroup.values()) {
                List<Action> actions = Action.getActionsGrouped(g);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", g.name().toLowerCase(Locale.ROOT));
                row.put("kind", "group");
                row.put("name", messages.group(viewer, g));
                row.put("icon", g.getItem().getType().name());
                row.put("granted", actions.stream().allMatch(trust::hasPermission));
                row.put("granted_count", (int) actions.stream().filter(trust::hasPermission).count());
                row.put("total", actions.size());
                rows.add(row);
            }
        }
        for (Action action : Action.getActionsGrouped(group)) {
            if (action != Action.ALL) rows.add(actionRow(viewer, action, trust));
        }
        return rows;
    }

    private Map<String, Object> actionRow(@Nullable Player viewer, Action action, Trust trust) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", action.name().toLowerCase(Locale.ROOT));
        row.put("kind", "action");
        row.put("name", messages.action(viewer, action));
        row.put("icon", action.getItem().getType().name());
        row.put("granted", trust.hasPermission(action));
        row.put("granted_count", trust.hasPermission(action) ? 1 : 0);
        row.put("total", 1);
        return row;
    }

    /** The trust {@code target} names on {@code land}: global, guild, or a player's (empty when they have none). */
    static @Nullable Trust trust(Land land, String target) {
        if (target.equalsIgnoreCase("global")) return land.getGlobalTrust();
        if (target.equalsIgnoreCase("guild")) return land.getGuildTrust();
        UUID uuid = uuid(target);
        return uuid == null ? null : land.getTrust(uuid);
    }

    // ---------------------------------------------------------------- bans, flags, links, chunks

    /** {@code land}: the banned players. */
    public List<Map<String, Object>> bans(@Nullable Player viewer, Map<String, Object> params) {
        if (viewer == null) return List.of(playerRow(UUID.fromString(SAMPLE_ID), "Alex"));
        Land land = managed(viewer, params);
        if (land == null) return List.of();
        return land.getBans().stream()
                .map(uuid -> playerRow(uuid, name(uuid)))
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** {@code land}: the flags its type supports, on or off. */
    public List<Map<String, Object>> flags(@Nullable Player viewer, Map<String, Object> params) {
        Land land = viewer == null ? sampleLand("Maison") : managed(viewer, params);
        if (land == null) return List.of();
        return Arrays.stream(Flag.values())
                .filter(flag -> flag.isEnabled(land))
                .map(flag -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", flag.name().toLowerCase(Locale.ROOT));
                    row.put("name", messages.flag(viewer, flag));
                    row.put("icon", flag.getItem().getType().name());
                    row.put("enabled", land.hasFlag(flag));
                    return row;
                })
                .toList();
    }

    /** {@code land}: one row per link type, with the land it borrows from. */
    public List<Map<String, Object>> links(@Nullable Player viewer, Map<String, Object> params) {
        Land land = viewer == null ? sampleLand("Maison") : managed(viewer, params);
        if (land == null) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LinkType type : LinkType.values()) {
            Land with = land.getLinkedLand(type);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", type.name().toLowerCase(Locale.ROOT));
            row.put("linked", with != null);
            row.put("with", with == null ? "" : with.getName());
            rows.add(row);
        }
        return rows;
    }

    /** {@code land}, {@code type}: the lands it may borrow that type from (the owner's others, not linked back). */
    public List<Map<String, Object>> linkCandidates(@Nullable Player viewer, Map<String, Object> params) {
        if (viewer == null) return List.of(landRow(sampleLand("Ferme")));
        Land land = managed(viewer, params);
        LinkType type = enumOf(LinkType.class, param(params, "type"));
        if (land == null || type == null) return List.of();
        return linkCandidates(viewer, land, type).stream()
                .sorted(Comparator.comparing(Land::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::landRow)
                .toList();
    }

    /** As MSLands chose them: the lands of the land's owner (or of the viewer for a system land), minus loops. */
    List<Land> linkCandidates(Player viewer, Land land, LinkType type) {
        UUID owner = land.getOwner() != null ? land.getOwner() : viewer.getUniqueId();
        return lands.getLands(owner).stream()
                .filter(other -> !other.equals(land))
                .filter(other -> !Objects.equals(other.getLinkedLand(type), land))
                .filter(other -> canManage(viewer, other))
                .toList();
    }

    /** {@code land}: its chunks on this server. */
    public List<Map<String, Object>> chunks(@Nullable Player viewer, Map<String, Object> params) {
        if (viewer == null) return List.of(chunkRow(new SChunk("server", "world", 3, -7)));
        Land land = managed(viewer, params);
        if (land == null || land instanceof SubLand) return List.of();
        return lands.getChunks(land).stream()
                .sorted(Comparator.comparing(SChunk::getWorld).thenComparingInt(SChunk::getX).thenComparingInt(SChunk::getZ))
                .map(this::chunkRow)
                .toList();
    }

    private Map<String, Object> chunkRow(SChunk chunk) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("server", chunk.getServer());
        row.put("world", chunk.getWorld());
        row.put("x", chunk.getX());
        row.put("z", chunk.getZ());
        row.put("block_x", chunk.getX() * 16);
        row.put("block_z", chunk.getZ() * 16);
        return row;
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> playerRow(UUID uuid, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", uuid.toString());
        row.put("name", name);
        return row;
    }

    /** The player's last known name, from the server's cache only (never a lookup). */
    static String name(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    private Land sampleLand(String name) {
        return new fr.iban.lands.model.land.SystemLand(UUID.fromString(SAMPLE_ID), name);
    }

    /**
     * {@code params.key} as text, empty when absent or still a placeholder ({@code {group}}: a menu
     * opened without that argument leaves the token as written).
     */
    static String param(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return "";
        String text = String.valueOf(value).trim();
        return text.startsWith("{") && text.endsWith("}") ? "" : text;
    }

    static @Nullable UUID uuid(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static <E extends Enum<E>> @Nullable E enumOf(Class<E> type, @Nullable String key) {
        if (key == null || key.isBlank()) return null;
        try {
            return Enum.valueOf(type, key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
