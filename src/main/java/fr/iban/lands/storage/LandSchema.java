package fr.iban.lands.storage;

import net.artisanmc.modules.api.database.DatabaseDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * Creates and upgrades ArtisanLands' tables in the database Artisan lends. Forward only, with
 * statements H2, MySQL and MariaDB run the same. MSLands' data is brought in afterwards by
 * {@link LegacyImport}, never in place: the {@code land_*} tables are never touched.
 *
 * <p>Plain JDBC, run once from {@code onEnable}, blocking.
 */
public final class LandSchema {

    public record Result(int fromVersion, int toVersion) {
        public boolean created() { return fromVersion == 0; }
        public boolean upgraded() { return fromVersion > 0 && toVersion > fromVersion; }
    }

    private final DataSource dataSource;
    private final DatabaseDialect dialect;
    private final LandTables t;

    public LandSchema(DataSource dataSource, DatabaseDialect dialect, LandTables tables) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.t = tables;
    }

    /**
     * Append, never edit a released step. Columns as wide as MSLands' and the same keys, so that
     * every row {@link LegacyImport} copies fits.
     */
    private List<List<String>> migrations() {
        String timestamp = dialect == DatabaseDialect.H2 ? "TIMESTAMP" : "DATETIME";
        String land = "FOREIGN KEY (land_id) REFERENCES " + t.lands() + "(id) ON DELETE CASCADE";
        return List.of(
                List.of(
                        "CREATE TABLE " + t.lands() + " ("
                                + "id CHAR(36) NOT NULL PRIMARY KEY, "
                                + "name VARCHAR(255) NULL, "
                                + "type VARCHAR(255) NOT NULL, "
                                + "owner_id CHAR(36) NULL, "
                                + "created_at " + timestamp + " DEFAULT CURRENT_TIMESTAMP NOT NULL)",
                        "CREATE INDEX " + t.prefix() + "lands_owner ON " + t.lands() + " (owner_id)",
                        "CREATE TABLE " + t.bans() + " ("
                                + "land_id CHAR(36) NOT NULL, "
                                + "uuid CHAR(36) NOT NULL, "
                                + "PRIMARY KEY (land_id, uuid), " + land + ")",
                        // uuid: a player's UUID, GLOBAL (everyone) or GUILD_MEMBER, as MSLands wrote it.
                        "CREATE TABLE " + t.trusts() + " ("
                                + "land_id CHAR(36) NOT NULL, "
                                + "uuid VARCHAR(36) NOT NULL, "
                                + "permission VARCHAR(255) NOT NULL, "
                                + "PRIMARY KEY (land_id, uuid, permission), " + land + ")",
                        "CREATE TABLE " + t.flags() + " ("
                                + "land_id CHAR(36) NOT NULL, "
                                + "flag VARCHAR(255) NOT NULL, "
                                + "PRIMARY KEY (land_id, flag), " + land + ")",
                        "CREATE TABLE " + t.links() + " ("
                                + "land_id CHAR(36) NOT NULL, "
                                + "linked_land_id CHAR(36) NOT NULL, "
                                + "link_type VARCHAR(255) NOT NULL, "
                                + "PRIMARY KEY (land_id, linked_land_id, link_type), " + land + ", "
                                + "FOREIGN KEY (linked_land_id) REFERENCES " + t.lands() + "(id) ON DELETE CASCADE)",
                        "CREATE TABLE " + t.chunks() + " ("
                                + "server VARCHAR(255) NOT NULL, "
                                + "world VARCHAR(255) NOT NULL, "
                                + "x INT NOT NULL, "
                                + "z INT NOT NULL, "
                                + "land_id CHAR(36) NOT NULL, "
                                + "created_at " + timestamp + " DEFAULT CURRENT_TIMESTAMP NOT NULL, "
                                + "PRIMARY KEY (server, world, x, z), " + land + ")",
                        "CREATE INDEX " + t.prefix() + "chunks_land ON " + t.chunks() + " (land_id)",
                        "CREATE TABLE " + t.worldDefaults() + " ("
                                + "server VARCHAR(255) NOT NULL, "
                                + "world VARCHAR(255) NOT NULL, "
                                + "land_id CHAR(36) NOT NULL, "
                                + "created_at " + timestamp + " DEFAULT CURRENT_TIMESTAMP NOT NULL, "
                                + "PRIMARY KEY (server, world), " + land + ")",
                        // id: the sub-land (a row of lands too), land_id: the land it sits in.
                        "CREATE TABLE " + t.sublands() + " ("
                                + "id CHAR(36) NOT NULL PRIMARY KEY, "
                                + "land_id CHAR(36) NOT NULL, "
                                + "server VARCHAR(255) NULL, "
                                + "world VARCHAR(255) NULL, "
                                + "x1 INT NULL, y1 INT NULL, z1 INT NULL, "
                                + "x2 INT NULL, y2 INT NULL, z2 INT NULL, "
                                + land + ", "
                                + "FOREIGN KEY (id) REFERENCES " + t.lands() + "(id) ON DELETE CASCADE)",
                        // uuid: a player or a guild.
                        "CREATE TABLE " + t.limits() + " ("
                                + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                                + "chunk_limit INT NOT NULL)",
                        "CREATE TABLE " + t.effects() + " ("
                                + "land_id CHAR(36) NOT NULL, "
                                + "effect VARCHAR(255) NOT NULL, "
                                + "amplifier INT NOT NULL, "
                                + "PRIMARY KEY (land_id, effect), " + land + ")",
                        "CREATE TABLE " + t.commands() + " ("
                                + "id CHAR(36) NOT NULL PRIMARY KEY, "
                                + "land_id CHAR(36) NOT NULL, "
                                + "command VARCHAR(255) NOT NULL, "
                                + "as_console BOOLEAN NOT NULL, " + land + ")",
                        // One row per import of MSLands' data (LegacyImport): it never runs twice.
                        "CREATE TABLE " + t.imports() + " ("
                                + "source VARCHAR(255) NOT NULL, "
                                + "imported_at " + timestamp + " NOT NULL, "
                                + "counts VARCHAR(255) NOT NULL)"));
    }

    public Result migrate() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            execute(c, List.of("CREATE TABLE IF NOT EXISTS " + t.schemaVersion() + " (version INT NOT NULL)"));
            int from = currentVersion(c);
            List<List<String>> steps = migrations();
            for (int i = from; i < steps.size(); i++) {
                execute(c, steps.get(i));
                try (PreparedStatement p = c.prepareStatement("INSERT INTO " + t.schemaVersion() + " (version) VALUES (?)")) {
                    p.setInt(1, i + 1);
                    p.executeUpdate();
                }
            }
            return new Result(from, Math.max(from, steps.size()));
        }
    }

    private int currentVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT MAX(version) FROM " + t.schemaVersion())) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    private static void execute(Connection c, List<String> statements) throws SQLException {
        for (String sql : statements) {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }
    }

    /**
     * Looked up in the connection's own database only: MySQL's metadata otherwise searches every
     * database of the server. The name is tried as written, then upper case (H2), then lower case.
     */
    static boolean tableExists(Connection c, String table) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        for (String name : spellings(table)) {
            try (ResultSet r = meta.getTables(c.getCatalog(), null, name, new String[]{"TABLE", "BASE TABLE"})) {
                if (r.next()) return true;
            }
        }
        return false;
    }

    private static List<String> spellings(String name) {
        return List.of(name, name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)).stream()
                .distinct().toList();
    }
}
