package fr.iban.lands.storage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Copies the lands of MSLands 1.1 (its eleven {@code land_*} tables, usually in the database of
 * the Core plugin) into ArtisanLands' tables, once.
 *
 * <p>Runs only when ArtisanLands' tables are empty and no import is recorded. The copy is one
 * transaction on the target: on any error nothing is kept and the next start tries again. Each
 * table's row count is checked before the commit. The source is only read: putting MSLands back
 * finds its data as it was.
 */
public final class LegacyImport {

    public enum Outcome { IMPORTED, ALREADY_IMPORTED, NO_SOURCE, TARGET_NOT_EMPTY }

    public record Result(Outcome outcome, Map<String, Integer> counts) {
        static Result of(Outcome outcome) {
            return new Result(outcome, Map.of());
        }
    }

    static final String LANDS = "land_lands";
    /** The id MSLands and ArtisanLands give the wilderness. */
    static final String WILDERNESS_ID = "00000000-0000-0000-0000-000000000000";

    @FunctionalInterface
    private interface RowCopier {
        void bind(ResultSet row, PreparedStatement insert) throws SQLException;
    }

    /** One source table, copied into one of ours. */
    private record Copy(String label, String source, String target, String columns, RowCopier copier) {
        String select() {
            return "SELECT " + columns + " FROM " + source;
        }

        String insert() {
            String placeholders = String.join(", ", java.util.Collections.nCopies(columns.split(",").length, "?"));
            return "INSERT INTO " + target + " (" + columns + ") VALUES (" + placeholders + ")";
        }
    }

    private final LandTables t;

    public LegacyImport(LandTables tables) {
        this.t = tables;
    }

    /**
     * @param source      where MSLands' tables are; may be the target's own data source
     * @param sourceLabel what the log and the import record say about it, e.g. {@code Core database}
     */
    public Result run(DataSource source, String sourceLabel, DataSource target) throws SQLException {
        try (Connection to = target.getConnection()) {
            if (count(to, t.imports()) > 0) return Result.of(Outcome.ALREADY_IMPORTED);
            try (Connection from = source.getConnection()) {
                if (!LandSchema.tableExists(from, LANDS)) return Result.of(Outcome.NO_SOURCE);
                // The wilderness alone (written at a start without MSLands' tables) is not a server's lands.
                if (countLandsBesidesWilderness(to) > 0) return Result.of(Outcome.TARGET_NOT_EMPTY);

                boolean autoCommit = to.getAutoCommit();
                to.setAutoCommit(false);
                try {
                    // The wilderness ArtisanLands may have written before: MSLands' own comes with the copy.
                    try (PreparedStatement p = to.prepareStatement("DELETE FROM " + t.lands() + " WHERE id = ?")) {
                        p.setString(1, WILDERNESS_ID);
                        p.executeUpdate();
                    }
                    Map<String, Integer> counts = copy(from, to);
                    try (PreparedStatement p = to.prepareStatement(
                            "INSERT INTO " + t.imports() + " (source, imported_at, counts) VALUES (?, ?, ?)")) {
                        p.setString(1, truncate(sourceLabel, 255));
                        p.setTimestamp(2, Timestamp.from(Instant.now()));
                        p.setString(3, truncate(counts.entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")), 255));
                        p.executeUpdate();
                    }
                    to.commit();
                    return new Result(Outcome.IMPORTED, counts);
                } catch (SQLException | RuntimeException e) {
                    to.rollback();
                    throw e;
                } finally {
                    to.setAutoCommit(autoCommit);
                }
            }
        }
    }

    /** Parents first, for the foreign keys. */
    private List<Copy> copies() {
        RowCopier landRow = (r, p) -> {
            p.setString(1, r.getString("id"));
            p.setString(2, r.getString("name"));
            p.setString(3, r.getString("type"));
            p.setString(4, r.getString("owner_id"));
            p.setTimestamp(5, createdAt(r));
        };
        return List.of(
                new Copy("lands", LANDS, t.lands(), "id, name, type, owner_id, created_at", landRow),
                new Copy("sublands", "land_sublands", t.sublands(), "id, land_id, server, world, x1, y1, z1, x2, y2, z2",
                        (r, p) -> {
                            p.setString(1, r.getString("id"));
                            p.setString(2, r.getString("land_id"));
                            p.setString(3, r.getString("server"));
                            p.setString(4, r.getString("world"));
                            for (int i = 0; i < 6; i++) {
                                String column = new String[]{"x1", "y1", "z1", "x2", "y2", "z2"}[i];
                                int value = r.getInt(column);
                                if (r.wasNull()) p.setNull(5 + i, Types.INTEGER);
                                else p.setInt(5 + i, value);
                            }
                        }),
                new Copy("bans", "land_bans", t.bans(), "land_id, uuid", strings(2)),
                new Copy("trusts", "land_trusts", t.trusts(), "land_id, uuid, permission", strings(3)),
                new Copy("flags", "land_flags", t.flags(), "land_id, flag", strings(2)),
                new Copy("links", "land_links", t.links(), "land_id, linked_land_id, link_type", strings(3)),
                new Copy("chunks", "land_chunks", t.chunks(), "server, world, x, z, land_id, created_at",
                        (r, p) -> {
                            p.setString(1, r.getString("server"));
                            p.setString(2, r.getString("world"));
                            p.setInt(3, r.getInt("x"));
                            p.setInt(4, r.getInt("z"));
                            p.setString(5, r.getString("land_id"));
                            p.setTimestamp(6, createdAt(r));
                        }),
                new Copy("world_defaults", "land_world_default_land_lands", t.worldDefaults(), "server, world, land_id, created_at",
                        (r, p) -> {
                            p.setString(1, r.getString("server"));
                            p.setString(2, r.getString("world"));
                            p.setString(3, r.getString("land_id"));
                            p.setTimestamp(4, createdAt(r));
                        }),
                new Copy("limits", "land_limits", t.limits(), "uuid, chunk_limit",
                        (r, p) -> {
                            p.setString(1, r.getString("uuid"));
                            // INT UNSIGNED in MSLands.
                            p.setInt(2, (int) Math.min(Integer.MAX_VALUE, Math.max(0, r.getLong("chunk_limit"))));
                        }),
                new Copy("effects", "land_effects", t.effects(), "land_id, effect, amplifier",
                        (r, p) -> {
                            p.setString(1, r.getString("land_id"));
                            p.setString(2, r.getString("effect"));
                            p.setInt(3, r.getInt("amplifier"));
                        }),
                new Copy("commands", "land_commands", t.commands(), "id, land_id, command, as_console",
                        (r, p) -> {
                            p.setString(1, r.getString("id"));
                            p.setString(2, r.getString("land_id"));
                            p.setString(3, r.getString("command"));
                            p.setBoolean(4, r.getBoolean("as_console"));
                        }));
    }

    private Map<String, Integer> copy(Connection from, Connection to) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Copy> copies = copies();
        for (Copy copy : copies) counts.put(copy.label(), copyRows(from, to, copy));
        // The copy must be whole: a row lost on the way rolls everything back.
        for (Copy copy : copies) check(from, to, copy.source(), copy.target());
        return counts;
    }

    private static RowCopier strings(int columns) {
        return (r, p) -> {
            for (int i = 1; i <= columns; i++) p.setString(i, r.getString(i));
        };
    }

    /** A source table MSLands never created on this server counts as empty. */
    private static int copyRows(Connection from, Connection to, Copy copy) throws SQLException {
        if (!LandSchema.tableExists(from, copy.source())) return 0;
        int rows = 0;
        try (Statement s = from.createStatement();
             ResultSet r = s.executeQuery(copy.select());
             PreparedStatement p = to.prepareStatement(copy.insert())) {
            while (r.next()) {
                copy.copier().bind(r, p);
                p.addBatch();
                if (++rows % 500 == 0) p.executeBatch();
            }
            p.executeBatch();
        }
        return rows;
    }

    private static void check(Connection from, Connection to, String source, String target) throws SQLException {
        int expected = LandSchema.tableExists(from, source) ? count(from, source) : 0;
        int copied = count(to, target);
        if (expected != copied) {
            throw new SQLException("Import of " + source + " incomplete: " + copied + " rows copied out of " + expected
                    + ". Nothing was kept; the next start tries again.");
        }
    }

    private static Timestamp createdAt(ResultSet r) throws SQLException {
        Timestamp at = r.getTimestamp("created_at");
        return at != null ? at : Timestamp.from(Instant.now());
    }

    private int countLandsBesidesWilderness(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM " + t.lands() + " WHERE id <> ?")) {
            p.setString(1, WILDERNESS_ID);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        }
    }

    static int count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
