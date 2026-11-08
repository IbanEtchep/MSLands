package fr.iban.lands.storage;

import net.artisanmc.modules.api.database.DatabaseDialect;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyImportTest {

    static final String WILDERNESS = "00000000-0000-0000-0000-000000000000";
    static final String HOME = "11111111-1111-1111-1111-111111111111";
    static final String FARM = "22222222-2222-2222-2222-222222222222";
    static final String SUB = "33333333-3333-3333-3333-333333333333";
    static final String OWNER = "44444444-4444-4444-4444-444444444444";

    private final LandTables tables = new LandTables("artisan_lands_");
    private DataSource source;
    private DataSource target;

    @BeforeEach
    void setUp() {
        source = h2("source");
        target = h2("target");
    }

    private static DataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1");
        return ds;
    }

    @Test
    void schemaIsCreatedOnceThenLeftAlone() throws SQLException {
        LandSchema.Result first = new LandSchema(target, DatabaseDialect.H2, tables).migrate();
        assertTrue(first.created());
        LandSchema.Result second = new LandSchema(target, DatabaseDialect.H2, tables).migrate();
        assertFalse(second.created());
        assertFalse(second.upgraded());
        assertEquals(first.toVersion(), second.toVersion());
    }

    @Test
    void copiesEveryTableOnceWithEqualCounts() throws SQLException {
        legacySchema(source);
        legacyRows(source);
        new LandSchema(target, DatabaseDialect.H2, tables).migrate();

        LegacyImport.Result result = new LegacyImport(tables).run(source, "test", target);

        assertEquals(LegacyImport.Outcome.IMPORTED, result.outcome());
        try (Connection from = source.getConnection(); Connection to = target.getConnection()) {
            assertEquals(LegacyImport.count(from, "land_lands"), LegacyImport.count(to, tables.lands()));
            assertEquals(2, LegacyImport.count(to, tables.chunks()));
            assertEquals(1, LegacyImport.count(to, tables.sublands()));
            assertEquals(2, LegacyImport.count(to, tables.trusts()));
            assertEquals(1, LegacyImport.count(to, tables.bans()));
            assertEquals(1, LegacyImport.count(to, tables.flags()));
            assertEquals(1, LegacyImport.count(to, tables.links()));
            assertEquals(1, LegacyImport.count(to, tables.worldDefaults()));
            assertEquals(1, LegacyImport.count(to, tables.limits()));
            assertEquals(1, LegacyImport.count(to, tables.effects()));
            assertEquals(1, LegacyImport.count(to, tables.commands()));
            assertEquals(1, LegacyImport.count(to, tables.imports()));
            try (Statement s = to.createStatement();
                 ResultSet r = s.executeQuery("SELECT name, owner_id FROM " + tables.lands() + " WHERE id = '" + HOME + "'")) {
                assertTrue(r.next());
                assertEquals("Maison", r.getString(1));
                assertEquals(OWNER, r.getString(2));
            }
        }
        // MSLands' tables are only read.
        try (Connection from = source.getConnection()) {
            assertEquals(4, LegacyImport.count(from, "land_lands"));
        }

        assertEquals(LegacyImport.Outcome.ALREADY_IMPORTED, new LegacyImport(tables).run(source, "test", target).outcome());
    }

    @Test
    void replacesAWildernessWrittenBeforeTheCopy() throws SQLException {
        legacySchema(source);
        legacyRows(source);
        new LandSchema(target, DatabaseDialect.H2, tables).migrate();
        exec(target, "INSERT INTO " + tables.lands() + " (id, name, type) VALUES ('" + WILDERNESS + "', 'Zone sauvage', 'SYSTEM')");

        assertEquals(LegacyImport.Outcome.IMPORTED, new LegacyImport(tables).run(source, "test", target).outcome());
        try (Connection to = target.getConnection()) {
            assertEquals(4, LegacyImport.count(to, tables.lands()));
        }
    }

    @Test
    void neverCopiesOverLandsAlreadyThere() throws SQLException {
        legacySchema(source);
        legacyRows(source);
        new LandSchema(target, DatabaseDialect.H2, tables).migrate();
        exec(target, "INSERT INTO " + tables.lands() + " (id, name, type) VALUES ('" + FARM + "', 'Autre', 'SYSTEM')");

        assertEquals(LegacyImport.Outcome.TARGET_NOT_EMPTY, new LegacyImport(tables).run(source, "test", target).outcome());
        try (Connection to = target.getConnection()) {
            assertEquals(1, LegacyImport.count(to, tables.lands()));
            assertEquals(0, LegacyImport.count(to, tables.imports()));
        }
    }

    @Test
    void nothingToCopyWithoutMSLandsTables() throws SQLException {
        new LandSchema(target, DatabaseDialect.H2, tables).migrate();
        assertEquals(LegacyImport.Outcome.NO_SOURCE, new LegacyImport(tables).run(source, "test", target).outcome());
    }

    @Test
    void copiesFromTheSameDatabase() throws SQLException {
        legacySchema(target);
        legacyRows(target);
        new LandSchema(target, DatabaseDialect.H2, tables).migrate();
        assertEquals(LegacyImport.Outcome.IMPORTED, new LegacyImport(tables).run(target, "artisan", target).outcome());
    }

    /** MSLands' tables as MariaDB had them (UUID columns), in what H2 accepts. */
    static void legacySchema(DataSource ds) throws SQLException {
        exec(ds,
                "CREATE TABLE land_lands(id UUID PRIMARY KEY NOT NULL, name VARCHAR(255), type VARCHAR(255) NOT NULL, owner_id UUID DEFAULT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE land_bans(land_id UUID NOT NULL, uuid UUID NOT NULL, PRIMARY KEY (land_id, uuid), FOREIGN KEY (land_id) REFERENCES land_lands(id) ON DELETE CASCADE)",
                "CREATE TABLE land_trusts(land_id UUID NOT NULL, uuid VARCHAR(36) NOT NULL, permission VARCHAR(255) NOT NULL, PRIMARY KEY (land_id, uuid, permission), FOREIGN KEY (land_id) REFERENCES land_lands(id) ON DELETE CASCADE)",
                "CREATE TABLE land_flags(land_id UUID NOT NULL, flag VARCHAR(255) NOT NULL, PRIMARY KEY (land_id, flag), FOREIGN KEY (land_id) REFERENCES land_lands(id) ON DELETE CASCADE)",
                "CREATE TABLE land_links(land_id UUID NOT NULL, linked_land_id UUID NOT NULL, link_type VARCHAR(255) NOT NULL, PRIMARY KEY (land_id, linked_land_id, link_type))",
                "CREATE TABLE land_chunks(`server` VARCHAR(255) NOT NULL, `world` VARCHAR(255) NOT NULL, x INT NOT NULL, z INT NOT NULL, land_id UUID NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`server`, `world`, x, z))",
                "CREATE TABLE land_world_default_land_lands(`server` VARCHAR(255) NOT NULL, `world` VARCHAR(255) NOT NULL, land_id UUID NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`server`, `world`))",
                "CREATE TABLE land_sublands(id UUID PRIMARY KEY NOT NULL, land_id UUID NOT NULL, `server` VARCHAR(255), `world` VARCHAR(255), x1 INT, y1 INT, z1 INT, x2 INT, y2 INT, z2 INT)",
                "CREATE TABLE land_limits(uuid UUID NOT NULL PRIMARY KEY, chunk_limit BIGINT NOT NULL)",
                "CREATE TABLE land_effects(land_id UUID NOT NULL, effect VARCHAR(255) NOT NULL, amplifier INT NOT NULL, PRIMARY KEY (land_id, effect))",
                "CREATE TABLE land_commands(id UUID PRIMARY KEY NOT NULL, land_id UUID NOT NULL, command VARCHAR(255) NOT NULL, as_console BOOLEAN NOT NULL)");
    }

    static void legacyRows(DataSource ds) throws SQLException {
        exec(ds,
                "INSERT INTO land_lands (id, name, type, owner_id) VALUES ('" + WILDERNESS + "', 'Zone sauvage', 'SYSTEM', NULL)",
                "INSERT INTO land_lands (id, name, type, owner_id) VALUES ('" + HOME + "', 'Maison', 'PLAYER', '" + OWNER + "')",
                "INSERT INTO land_lands (id, name, type, owner_id) VALUES ('" + FARM + "', 'Ferme', 'PLAYER', '" + OWNER + "')",
                "INSERT INTO land_lands (id, name, type, owner_id) VALUES ('" + SUB + "', 'Cave', 'SUBLAND', '" + OWNER + "')",
                "INSERT INTO land_sublands VALUES ('" + SUB + "', '" + HOME + "', 'survie', 'world', 0, 10, 0, 5, 20, 5)",
                "INSERT INTO land_bans VALUES ('" + HOME + "', '" + UUID.randomUUID() + "')",
                "INSERT INTO land_trusts VALUES ('" + HOME + "', '" + UUID.randomUUID() + "', 'BLOCK_BREAK')",
                "INSERT INTO land_trusts VALUES ('" + HOME + "', 'GLOBAL', 'USE')",
                "INSERT INTO land_flags VALUES ('" + HOME + "', 'PVP')",
                "INSERT INTO land_links VALUES ('" + FARM + "', '" + HOME + "', 'TRUSTS')",
                "INSERT INTO land_chunks (`server`, `world`, x, z, land_id) VALUES ('survie', 'world', 0, 0, '" + HOME + "')",
                "INSERT INTO land_chunks (`server`, `world`, x, z, land_id) VALUES ('survie', 'world', 1, 0, '" + FARM + "')",
                "INSERT INTO land_world_default_land_lands (`server`, `world`, land_id) VALUES ('survie', 'world_nether', '" + WILDERNESS + "')",
                "INSERT INTO land_limits VALUES ('" + OWNER + "', 4294967295)",
                "INSERT INTO land_effects VALUES ('" + HOME + "', 'SPEED', 1)",
                "INSERT INTO land_commands VALUES ('" + UUID.randomUUID() + "', '" + HOME + "', 'say hi', TRUE)");
    }

    static void exec(DataSource ds, String... statements) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String sql : statements) s.execute(sql);
        }
    }
}
