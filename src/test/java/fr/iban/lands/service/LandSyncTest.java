package fr.iban.lands.service;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.enums.LinkType;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.PlayerLand;
import fr.iban.lands.model.land.SubLand;
import fr.iban.lands.storage.LandSchema;
import fr.iban.lands.storage.LandTables;
import fr.iban.lands.storage.SqlStorage;
import net.artisanmc.modules.api.database.DatabaseDialect;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two servers on one database: what server A saves, server B reads again and applies, as
 * {@code LandSync} does once Artisan delivers A's message.
 */
class LandSyncTest {

    private static final UUID OWNER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private LandRepositoryImpl a;
    private LandRepositoryImpl b;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sync" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1");
        LandTables tables = new LandTables("artisan_lands_");
        new LandSchema(ds, DatabaseDialect.H2, tables).migrate();
        a = new LandRepositoryImpl(plugin(), new SqlStorage(ds, tables, "a", Logger.getLogger("a")));
        b = new LandRepositoryImpl(plugin(), new SqlStorage(ds, tables, "b", Logger.getLogger("b")));
        LandRepositoryImpl receiver = b;
        a.setChanges(new LandRepositoryImpl.LandChanges() {
            @Override
            public void landChanged(UUID id) {
                receiver.apply(receiver.read(id));
            }

            @Override
            public void worldsChanged() {
                receiver.applyWorldDefaults(receiver.readWorldDefaults());
            }
        });
    }

    /** Saves at once, on the calling thread. */
    private static LandsPlugin plugin() {
        LandsPlugin plugin = mock(LandsPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("lands"));
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).runAsyncQueued(any());
        return plugin;
    }

    @Test
    void aNewLandReachesTheOtherServer() {
        Land home = new PlayerLand(UUID.randomUUID(), OWNER, "Maison");
        a.addLand(home);

        Land seen = b.getLandById(home.getId()).orElseThrow();
        assertEquals("Maison", seen.getName());
        assertEquals(OWNER, seen.getOwner());
    }

    @Test
    void aChangeKeepsTheInstanceOtherPluginsHold() {
        Land home = new PlayerLand(UUID.randomUUID(), OWNER, "Maison");
        a.addLand(home);
        Land held = b.getLandById(home.getId()).orElseThrow();

        UUID banned = UUID.randomUUID();
        a.addBan(home, banned);
        home.setName("Chateau");
        a.updateLand(home);

        assertSame(held, b.getLandById(home.getId()).orElseThrow());
        assertTrue(held.isBanned(banned));
        assertEquals("Chateau", held.getName());

        a.removeBan(home, banned);
        assertFalse(held.isBanned(banned));
    }

    @Test
    void subLandsAndLinksFollow() {
        Land home = new PlayerLand(UUID.randomUUID(), OWNER, "Maison");
        Land farm = new PlayerLand(UUID.randomUUID(), OWNER, "Ferme");
        a.addLand(home);
        a.addLand(farm);

        SubLand shed = new SubLand(UUID.randomUUID(), "Abri");
        a.addLand(shed);
        shed.setSuperLand(home);
        shed.setCuboid(null, null);
        home.getSubLands().put(shed.getId(), shed);
        a.updateLand(shed);

        Land homeOnB = b.getLandById(home.getId()).orElseThrow();
        SubLand shedOnB = (SubLand) b.getLandById(shed.getId()).orElseThrow();
        assertSame(homeOnB, shedOnB.getSuperLand());
        assertSame(shedOnB, homeOnB.getSubLands().get(shed.getId()));
        assertEquals(OWNER, shedOnB.getOwner());

        a.addLink(farm, LinkType.TRUSTS, home);
        assertSame(homeOnB, b.getLandById(farm.getId()).orElseThrow().getLinks().get(LinkType.TRUSTS));
        a.removeLink(farm, LinkType.TRUSTS);
        assertFalse(b.getLandById(farm.getId()).orElseThrow().getLinks().containsKey(LinkType.TRUSTS));
    }

    @Test
    void aDeletedLandGoesWithItsSubLands() {
        Land home = new PlayerLand(UUID.randomUUID(), OWNER, "Maison");
        a.addLand(home);
        SubLand shed = new SubLand(UUID.randomUUID(), "Abri");
        a.addLand(shed);
        shed.setSuperLand(home);
        home.getSubLands().put(shed.getId(), shed);
        a.updateLand(shed);
        assertNotNull(b.getLandById(shed.getId()).orElse(null));

        a.deleteLand(home);

        assertTrue(b.getLandById(home.getId()).isEmpty());
        assertTrue(b.getLandById(shed.getId()).isEmpty());
    }
}
