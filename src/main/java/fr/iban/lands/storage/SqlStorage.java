package fr.iban.lands.storage;

import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.enums.LinkType;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.GuildLand;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.LandEnterCommand;
import fr.iban.lands.model.land.PlayerLand;
import fr.iban.lands.model.land.SubLand;
import fr.iban.lands.model.land.SystemLand;
import fr.iban.lands.utils.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The lands in the database Artisan lends ({@link LandTables}), in SQL H2, MySQL and MariaDB run
 * the same: no {@code ON DUPLICATE KEY}, an upsert is an update then an insert (the writes of one
 * server go through one thread). A failed write is logged with what it was; the memory keeps the
 * change until the next start.
 */
public class SqlStorage implements Storage {

    /** How {@link #addGlobalTrust} and {@link #addGuildTrust} are written in the trusts' uuid column, as MSLands did. */
    static final String GLOBAL = "GLOBAL";
    static final String GUILD_MEMBER = "GUILD_MEMBER";

    private final DataSource ds;
    private final LandTables t;
    private final String serverName;
    private final Logger logger;

    public SqlStorage(DataSource dataSource, LandTables tables, String serverName, Logger logger) {
        this.ds = dataSource;
        this.t = tables;
        this.serverName = serverName;
        this.logger = logger;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet row) throws SQLException;
    }

    private int update(String sql, Binder binder) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            binder.bind(p);
            return p.executeUpdate();
        }
    }

    /** A write that logs instead of throwing: what failed, and that memory still has it. */
    private void write(String what, String sql, Binder binder) {
        try {
            update(sql, binder);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not save " + what + " (kept until the next restart)", e);
        }
    }

    /** {@code updateSql}, and {@code insertSql} when it changed no row. */
    private void upsert(String what, String updateSql, Binder updateBinder, String insertSql, Binder insertBinder) {
        try {
            if (update(updateSql, updateBinder) == 0) update(insertSql, insertBinder);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not save " + what + " (kept until the next restart)", e);
        }
    }

    private void query(String sql, Binder binder, RowReader reader) {
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            binder.bind(p);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) reader.read(r);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the lands: " + sql, e);
        }
    }

    private static final Binder NONE = p -> { };

    // ---------------------------------------------------------------- reads

    @Override
    public Map<UUID, Land> getLands() {
        Map<UUID, Land> lands = new HashMap<>();
        query("SELECT id, name, type, owner_id, created_at FROM " + t.lands(), NONE, r -> {
            Land land = landFrom(r);
            if (land != null) lands.put(land.getId(), land);
        });
        attachDetails(lands, null);
        return lands;
    }

    @Override
    public @Nullable Land getLand(UUID landId) {
        Map<UUID, Land> lands = new HashMap<>();
        query("SELECT id, name, type, owner_id, created_at FROM " + t.lands() + " WHERE id = ?",
                p -> p.setString(1, landId.toString()), r -> {
                    Land land = landFrom(r);
                    if (land != null) lands.put(land.getId(), land);
                });
        attachDetails(lands, landId);
        return lands.get(landId);
    }

    /** Trusts, flags, bans, effects, commands and sub-land areas, one query per table. */
    private void attachDetails(Map<UUID, Land> lands, @Nullable UUID only) {
        String where = only != null ? " WHERE land_id = ?" : "";
        Binder bind = only != null ? p -> p.setString(1, only.toString()) : NONE;

        query("SELECT land_id, uuid, permission FROM " + t.trusts() + where, bind, r -> {
            Land land = lands.get(uuid(r.getString("land_id")));
            if (land == null) return;
            Action action = enumOf(Action.class, r.getString("permission"));
            if (action == null) {
                logger.warning("Unknown permission " + r.getString("permission") + " on land " + land.getId() + ": ignored.");
                return;
            }
            String who = r.getString("uuid");
            if (GLOBAL.equals(who)) {
                land.trust(action);
            } else if (GUILD_MEMBER.equals(who)) {
                land.trustGuild(action);
            } else {
                UUID player = uuid(who);
                if (player != null) land.trust(player, action);
            }
        });
        query("SELECT land_id, flag FROM " + t.flags() + where, bind, r -> {
            Land land = lands.get(uuid(r.getString("land_id")));
            Flag flag = enumOf(Flag.class, r.getString("flag"));
            if (land != null && flag != null) land.getFlags().add(flag);
        });
        query("SELECT land_id, uuid FROM " + t.bans() + where, bind, r -> {
            Land land = lands.get(uuid(r.getString("land_id")));
            UUID banned = uuid(r.getString("uuid"));
            if (land != null && banned != null) land.getBans().add(banned);
        });
        query("SELECT land_id, effect, amplifier FROM " + t.effects() + where, bind, r -> {
            Land land = lands.get(uuid(r.getString("land_id")));
            if (land == null) return;
            String effect = r.getString("effect");
            NamespacedKey key = NamespacedKey.fromString(effect.toLowerCase(Locale.ROOT));
            PotionEffectType type = key != null ? Registry.EFFECT.get(key) : null;
            if (type == null) {
                logger.warning("Unknown potion effect " + effect + " on land " + land.getName() + ": ignored.");
                return;
            }
            land.addEffect(type, r.getInt("amplifier"));
        });
        query("SELECT id, land_id, command, as_console FROM " + t.commands() + where, bind, r -> {
            Land land = lands.get(uuid(r.getString("land_id")));
            UUID id = uuid(r.getString("id"));
            if (land != null && id != null) {
                land.getEnterCommands().add(new LandEnterCommand(id, r.getString("command"), r.getBoolean("as_console")));
            }
        });
        String subWhere = only != null ? " WHERE id = ?" : "";
        query("SELECT id, server, world, x1, y1, z1, x2, y2, z2 FROM " + t.sublands() + subWhere, bind, r -> {
            if (!(lands.get(uuid(r.getString("id"))) instanceof SubLand subLand)) return;
            String server = r.getString("server");
            String worldName = r.getString("world");
            World world = worldName != null ? Bukkit.getWorld(worldName) : null;
            // Another server's world is not loaded here: the area stays unknown, its server known.
            Cuboid cuboid = world == null ? null : new Cuboid(world,
                    r.getInt("x1"), r.getInt("y1"), r.getInt("z1"), r.getInt("x2"), r.getInt("y2"), r.getInt("z2"));
            subLand.setCuboid(cuboid, server);
        });
    }

    private @Nullable Land landFrom(ResultSet r) throws SQLException {
        UUID id = uuid(r.getString("id"));
        LandType type = enumOf(LandType.class, r.getString("type"));
        if (id == null || type == null) {
            logger.warning("Land " + r.getString("id") + " of unknown type " + r.getString("type") + ": ignored.");
            return null;
        }
        String name = r.getString("name");
        UUID owner = uuid(r.getString("owner_id"));
        Land land = switch (type) {
            case PLAYER -> owner != null ? new PlayerLand(id, owner, name) : null;
            case GUILD -> owner != null ? new GuildLand(id, owner, name) : null;
            case SYSTEM -> new SystemLand(id, name);
            case SUBLAND -> new SubLand(id, name);
        };
        if (land == null) {
            logger.warning("Land " + id + " (" + type + ") has no owner: ignored.");
            return null;
        }
        Timestamp createdAt = r.getTimestamp("created_at");
        if (createdAt != null) land.setCreatedAt(new Date(createdAt.getTime()));
        return land;
    }

    @Override
    public Map<UUID, UUID> getSubLands() {
        Map<UUID, UUID> sublands = new HashMap<>();
        query("SELECT id, land_id FROM " + t.sublands(), NONE, r -> {
            UUID id = uuid(r.getString("id"));
            UUID landId = uuid(r.getString("land_id"));
            if (id != null && landId != null) sublands.put(id, landId);
        });
        return sublands;
    }

    @Override
    public Map<UUID, Map<LinkType, UUID>> getLinks() {
        Map<UUID, Map<LinkType, UUID>> links = new HashMap<>();
        query("SELECT land_id, linked_land_id, link_type FROM " + t.links(), NONE, r -> {
            UUID landId = uuid(r.getString("land_id"));
            UUID linked = uuid(r.getString("linked_land_id"));
            LinkType type = enumOf(LinkType.class, r.getString("link_type"));
            if (landId != null && linked != null && type != null) {
                links.computeIfAbsent(landId, k -> new EnumMap<>(LinkType.class)).put(type, linked);
            }
        });
        return links;
    }

    @Override
    public @Nullable UUID getSuperLandId(UUID subLandId) {
        UUID[] superLand = new UUID[1];
        query("SELECT land_id FROM " + t.sublands() + " WHERE id = ?", p -> p.setString(1, subLandId.toString()),
                r -> superLand[0] = uuid(r.getString("land_id")));
        return superLand[0];
    }

    @Override
    public Map<LinkType, UUID> getLinks(UUID landId) {
        Map<LinkType, UUID> links = new EnumMap<>(LinkType.class);
        query("SELECT linked_land_id, link_type FROM " + t.links() + " WHERE land_id = ?",
                p -> p.setString(1, landId.toString()), r -> {
                    UUID linked = uuid(r.getString("linked_land_id"));
                    LinkType type = enumOf(LinkType.class, r.getString("link_type"));
                    if (linked != null && type != null) links.put(type, linked);
                });
        return links;
    }

    @Override
    public Map<SChunk, UUID> getChunks() {
        Map<SChunk, UUID> chunks = new HashMap<>();
        query("SELECT server, world, x, z, land_id, created_at FROM " + t.chunks() + " WHERE server = ?",
                p -> p.setString(1, serverName), r -> {
                    UUID landId = uuid(r.getString("land_id"));
                    if (landId == null) return;
                    Timestamp at = r.getTimestamp("created_at");
                    chunks.put(new SChunk(r.getString("server"), r.getString("world"), r.getInt("x"), r.getInt("z"),
                            at != null ? new Date(at.getTime()) : null), landId);
                });
        return chunks;
    }

    @Override
    public Map<String, UUID> getWorldsDefaultLands() {
        Map<String, UUID> worlds = new HashMap<>();
        query("SELECT world, land_id FROM " + t.worldDefaults() + " WHERE server = ?",
                p -> p.setString(1, serverName), r -> {
                    UUID landId = uuid(r.getString("land_id"));
                    if (landId != null) worlds.put(r.getString("world"), landId);
                });
        return worlds;
    }

    @Override
    public int getChunkCount(UUID owner) {
        int[] count = {0};
        query("SELECT COUNT(*) FROM " + t.chunks() + " c JOIN " + t.lands() + " l ON c.land_id = l.id WHERE l.owner_id = ?",
                p -> p.setString(1, owner.toString()), r -> count[0] = r.getInt(1));
        return count[0];
    }

    @Override
    public int getChunkLimit(UUID uuid) {
        int[] limit = {0};
        query("SELECT chunk_limit FROM " + t.limits() + " WHERE uuid = ?",
                p -> p.setString(1, uuid.toString()), r -> limit[0] = r.getInt(1));
        return limit[0];
    }

    // ---------------------------------------------------------------- writes

    @Override
    public void setWorldDefaultLand(String world, Land land) {
        upsert("the default land of " + world,
                "UPDATE " + t.worldDefaults() + " SET land_id = ? WHERE server = ? AND world = ?", p -> {
                    p.setString(1, land.getId().toString());
                    p.setString(2, serverName);
                    p.setString(3, world);
                },
                "INSERT INTO " + t.worldDefaults() + " (server, world, land_id, created_at) VALUES (?, ?, ?, ?)", p -> {
                    p.setString(1, serverName);
                    p.setString(2, world);
                    p.setString(3, land.getId().toString());
                    p.setTimestamp(4, Timestamp.from(Instant.now()));
                });
    }

    @Override
    public void addLand(Land land) {
        write("land " + land.getName(),
                "INSERT INTO " + t.lands() + " (id, name, type, owner_id, created_at) VALUES (?, ?, ?, ?, ?)", p -> {
                    p.setString(1, land.getId().toString());
                    p.setString(2, land.getName());
                    p.setString(3, land.getType().name());
                    if (land.getOwner() != null) p.setString(4, land.getOwner().toString());
                    else p.setNull(4, Types.CHAR);
                    p.setTimestamp(5, new Timestamp(land.getCreatedAt().getTime()));
                });
        if (land instanceof SubLand subLand && subLand.getSuperLand() != null) updateSublandRegion(subLand);
    }

    @Override
    public void deleteLand(Land land) {
        // The foreign keys remove its chunks, trusts, flags, bans, links, sub-land row, effects and commands.
        write("the deletion of land " + land.getName(), "DELETE FROM " + t.lands() + " WHERE id = ?",
                p -> p.setString(1, land.getId().toString()));
    }

    @Override
    public void updateLand(Land land) {
        write("the name of land " + land.getId(), "UPDATE " + t.lands() + " SET name = ? WHERE id = ?", p -> {
            p.setString(1, land.getName());
            p.setString(2, land.getId().toString());
        });
        if (land instanceof SubLand subLand && subLand.getSuperLand() != null) updateSublandRegion(subLand);
    }

    @Override
    public void updateSublandRegion(SubLand subland) {
        Cuboid cuboid = subland.getCuboid();
        Binder area = p -> {
            p.setString(1, subland.getServer());
            if (cuboid == null) {
                p.setNull(2, Types.VARCHAR);
                for (int i = 3; i <= 8; i++) p.setNull(i, Types.INTEGER);
            } else {
                p.setString(2, cuboid.getWorld().getName());
                p.setInt(3, cuboid.getLowerX());
                p.setInt(4, cuboid.getLowerY());
                p.setInt(5, cuboid.getLowerZ());
                p.setInt(6, cuboid.getUpperX());
                p.setInt(7, cuboid.getUpperY());
                p.setInt(8, cuboid.getUpperZ());
            }
        };
        upsert("the area of sub-land " + subland.getName(),
                "UPDATE " + t.sublands() + " SET server = ?, world = ?, x1 = ?, y1 = ?, z1 = ?, x2 = ?, y2 = ?, z2 = ? WHERE id = ?", p -> {
                    area.bind(p);
                    p.setString(9, subland.getId().toString());
                },
                "INSERT INTO " + t.sublands() + " (server, world, x1, y1, z1, x2, y2, z2, id, land_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", p -> {
                    area.bind(p);
                    p.setString(9, subland.getId().toString());
                    p.setString(10, subland.getSuperLand().getId().toString());
                });
    }

    @Override
    public void setChunk(Land land, SChunk chunk) {
        Timestamp at = Timestamp.from(chunk.getCreatedAt() != null ? chunk.getCreatedAt().toInstant() : Instant.now());
        upsert("a claim of " + land.getName(),
                "UPDATE " + t.chunks() + " SET land_id = ? WHERE server = ? AND world = ? AND x = ? AND z = ?", p -> {
                    p.setString(1, land.getId().toString());
                    bindChunk(p, 2, chunk);
                },
                "INSERT INTO " + t.chunks() + " (server, world, x, z, land_id, created_at) VALUES (?, ?, ?, ?, ?, ?)", p -> {
                    bindChunk(p, 1, chunk);
                    p.setString(5, land.getId().toString());
                    p.setTimestamp(6, at);
                });
    }

    @Override
    public void removeChunk(SChunk chunk) {
        write("an unclaim", "DELETE FROM " + t.chunks() + " WHERE server = ? AND world = ? AND x = ? AND z = ?",
                p -> bindChunk(p, 1, chunk));
    }

    private static void bindChunk(PreparedStatement p, int from, SChunk chunk) throws SQLException {
        p.setString(from, chunk.getServer());
        p.setString(from + 1, chunk.getWorld());
        p.setInt(from + 2, chunk.getX());
        p.setInt(from + 3, chunk.getZ());
    }

    @Override
    public void addTrust(Land land, UUID uuid, Action action) {
        addTrust(land, uuid.toString(), action);
    }

    @Override
    public void removeTrust(Land land, UUID uuid, Action action) {
        removeTrust(land, uuid.toString(), action);
    }

    @Override
    public void addGlobalTrust(Land land, Action action) {
        addTrust(land, GLOBAL, action);
    }

    @Override
    public void removeGlobalTrust(Land land, Action action) {
        removeTrust(land, GLOBAL, action);
    }

    @Override
    public void addGuildTrust(Land land, Action action) {
        addTrust(land, GUILD_MEMBER, action);
    }

    @Override
    public void removeGuildTrust(Land land, Action action) {
        removeTrust(land, GUILD_MEMBER, action);
    }

    /** Idempotent: granting twice keeps one row. */
    private void addTrust(Land land, String who, Action action) {
        removeTrust(land, who, action);
        write("a permission of " + land.getName(),
                "INSERT INTO " + t.trusts() + " (land_id, uuid, permission) VALUES (?, ?, ?)", p -> {
                    p.setString(1, land.getId().toString());
                    p.setString(2, who);
                    p.setString(3, action.name());
                });
    }

    private void removeTrust(Land land, String who, Action action) {
        write("a permission of " + land.getName(),
                "DELETE FROM " + t.trusts() + " WHERE land_id = ? AND uuid = ? AND permission = ?", p -> {
                    p.setString(1, land.getId().toString());
                    p.setString(2, who);
                    p.setString(3, action.name());
                });
    }

    @Override
    public void addFlag(Land land, Flag flag) {
        removeFlag(land, flag);
        write("a flag of " + land.getName(), "INSERT INTO " + t.flags() + " (land_id, flag) VALUES (?, ?)", p -> {
            p.setString(1, land.getId().toString());
            p.setString(2, flag.name());
        });
    }

    @Override
    public void removeFlag(Land land, Flag flag) {
        write("a flag of " + land.getName(), "DELETE FROM " + t.flags() + " WHERE land_id = ? AND flag = ?", p -> {
            p.setString(1, land.getId().toString());
            p.setString(2, flag.name());
        });
    }

    @Override
    public void addBan(Land land, UUID uuid) {
        removeBan(land, uuid);
        write("a ban of " + land.getName(), "INSERT INTO " + t.bans() + " (land_id, uuid) VALUES (?, ?)", p -> {
            p.setString(1, land.getId().toString());
            p.setString(2, uuid.toString());
        });
    }

    @Override
    public void removeBan(Land land, UUID uuid) {
        write("a ban of " + land.getName(), "DELETE FROM " + t.bans() + " WHERE land_id = ? AND uuid = ?", p -> {
            p.setString(1, land.getId().toString());
            p.setString(2, uuid.toString());
        });
    }

    @Override
    public void addLink(Land land, LinkType link, Land with) {
        removeLink(land, link);
        write("a link of " + land.getName(),
                "INSERT INTO " + t.links() + " (land_id, linked_land_id, link_type) VALUES (?, ?, ?)", p -> {
                    p.setString(1, land.getId().toString());
                    p.setString(2, with.getId().toString());
                    p.setString(3, link.name());
                });
    }

    @Override
    public void removeLink(Land land, LinkType link) {
        write("a link of " + land.getName(), "DELETE FROM " + t.links() + " WHERE land_id = ? AND link_type = ?", p -> {
            p.setString(1, land.getId().toString());
            p.setString(2, link.name());
        });
    }

    @Override
    public void setChunkLimit(UUID uuid, int limit) {
        int value = Math.max(0, limit);
        upsert("the claim limit of " + uuid,
                "UPDATE " + t.limits() + " SET chunk_limit = ? WHERE uuid = ?", p -> {
                    p.setInt(1, value);
                    p.setString(2, uuid.toString());
                },
                "INSERT INTO " + t.limits() + " (uuid, chunk_limit) VALUES (?, ?)", p -> {
                    p.setString(1, uuid.toString());
                    p.setInt(2, value);
                });
    }

    @Override
    public void increaseChunkLimit(UUID uuid, int amount) {
        upsert("the claim limit of " + uuid,
                "UPDATE " + t.limits() + " SET chunk_limit = chunk_limit + ? WHERE uuid = ?", p -> {
                    p.setInt(1, amount);
                    p.setString(2, uuid.toString());
                },
                "INSERT INTO " + t.limits() + " (uuid, chunk_limit) VALUES (?, ?)", p -> {
                    p.setString(1, uuid.toString());
                    p.setInt(2, Math.max(0, amount));
                });
    }

    /** Never below 0 (MSLands' column was unsigned); no row yet: 0. */
    @Override
    public void decreaseChunkLimit(UUID uuid, int amount) {
        upsert("the claim limit of " + uuid,
                "UPDATE " + t.limits() + " SET chunk_limit = CASE WHEN chunk_limit > ? THEN chunk_limit - ? ELSE 0 END WHERE uuid = ?", p -> {
                    p.setInt(1, amount);
                    p.setInt(2, amount);
                    p.setString(3, uuid.toString());
                },
                "INSERT INTO " + t.limits() + " (uuid, chunk_limit) VALUES (?, 0)", p -> p.setString(1, uuid.toString()));
    }

    @Override
    public void addEffect(Land land, String effect, int amplifier) {
        upsert("an effect of " + land.getName(),
                "UPDATE " + t.effects() + " SET amplifier = ? WHERE land_id = ? AND effect = ?", p -> {
                    p.setInt(1, amplifier);
                    p.setString(2, land.getId().toString());
                    p.setString(3, effect);
                },
                "INSERT INTO " + t.effects() + " (land_id, effect, amplifier) VALUES (?, ?, ?)", p -> {
                    p.setString(1, land.getId().toString());
                    p.setString(2, effect);
                    p.setInt(3, amplifier);
                });
    }

    @Override
    public void removeEffect(Land land, String effect) {
        write("an effect of " + land.getName(), "DELETE FROM " + t.effects() + " WHERE land_id = ? AND effect = ?", p -> {
            p.setString(1, land.getId().toString());
            p.setString(2, effect);
        });
    }

    @Override
    public void addCommand(Land land, LandEnterCommand command) {
        write("an enter command of " + land.getName(),
                "INSERT INTO " + t.commands() + " (id, land_id, command, as_console) VALUES (?, ?, ?, ?)", p -> {
                    p.setString(1, command.getUniqueId().toString());
                    p.setString(2, land.getId().toString());
                    p.setString(3, command.getCommand());
                    p.setBoolean(4, command.isAsConsole());
                });
    }

    @Override
    public void removeCommand(LandEnterCommand command) {
        write("the removal of an enter command", "DELETE FROM " + t.commands() + " WHERE id = ?",
                p -> p.setString(1, command.getUniqueId().toString()));
    }

    // ---------------------------------------------------------------- helpers

    private static @Nullable UUID uuid(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> @Nullable E enumOf(Class<E> type, @Nullable String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
