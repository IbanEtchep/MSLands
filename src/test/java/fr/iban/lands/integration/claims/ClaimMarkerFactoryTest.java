package fr.iban.lands.integration.claims;

import fr.iban.lands.enums.LandType;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.GuildLand;
import fr.iban.lands.model.land.PlayerLand;
import fr.iban.lands.model.land.SubLand;
import fr.iban.lands.model.land.SystemLand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMarkerFactoryTest {

    private ClaimMarkerFactory factory;
    private final SChunk chunk = new SChunk("survival", "world", 2, -3);

    @BeforeEach
    void setUp() {
        factory = new ClaimMarkerFactory(settings(), land -> "Alice & Bob");
    }

    @ParameterizedTest
    @CsvSource({
            "2,-3,32,-48,48,-32,chunk:2:-3",
            "-2,3,-32,48,-16,64,chunk:-2:3"
    })
    void createsExactChunkRectangles(
            int chunkX, int chunkZ,
            int minX, int minZ, int maxX, int maxZ,
            String markerId
    ) {
        SChunk chunk = new SChunk("survival", "world", chunkX, chunkZ);
        PlayerLand land = new PlayerLand(UUID.randomUUID(), UUID.randomUUID(), "Maison");

        ClaimMarkerDescriptor marker = factory.create(chunk, land).orElseThrow();

        assertAll(
                () -> assertEquals(markerId, marker.id()),
                () -> assertEquals("world", marker.world()),
                () -> assertEquals(minX, marker.minX()),
                () -> assertEquals(minZ, marker.minZ()),
                () -> assertEquals(maxX, marker.maxX()),
                () -> assertEquals(maxZ, marker.maxZ()),
                () -> assertEquals("Maison", marker.label()),
                () -> assertEquals(settings().style(LandType.PLAYER), marker.style())
        );
    }

    @Test
    void excludesSublands() {
        assertTrue(factory.create(chunk, new SubLand(UUID.randomUUID(), "Mine")).isEmpty());
    }

    @Test
    void createsEscapedPlayerDetail() {
        PlayerLand land = new PlayerLand(UUID.randomUUID(), UUID.randomUUID(), "Maison <nord>");

        ClaimMarkerDescriptor marker = factory.create(chunk, land).orElseThrow();

        assertEquals(
                "<b>Maison &lt;nord&gt;</b><br>Type: Joueur<br>Propriétaire: Alice &amp; Bob",
                marker.detail()
        );
    }

    @Test
    void createsGuildAndSystemDetails() {
        ClaimMarkerFactory guildFactory = new ClaimMarkerFactory(settings(), land -> "Les Bleus");

        ClaimMarkerDescriptor guild = guildFactory.create(
                chunk,
                new GuildLand(UUID.randomUUID(), UUID.randomUUID(), "Citadelle")
        ).orElseThrow();
        ClaimMarkerDescriptor system = factory.create(
                chunk,
                new SystemLand(UUID.randomUUID(), "Spawn")
        ).orElseThrow();

        assertAll(
                () -> assertEquals("<b>Citadelle</b><br>Type: Guilde<br>Guilde: Les Bleus", guild.detail()),
                () -> assertEquals("<b>Spawn</b><br>Type: Système", system.detail())
        );
    }

    @Test
    void escapesEveryDynamicHtmlCharacter() {
        ClaimMarkerFactory escapingFactory = new ClaimMarkerFactory(settings(), land -> "Bob &<>\"'");
        PlayerLand land = new PlayerLand(UUID.randomUUID(), UUID.randomUUID(), "Maison &<>\"'");

        ClaimMarkerDescriptor marker = escapingFactory.create(chunk, land).orElseThrow();

        assertEquals(
                "<b>Maison &amp;&lt;&gt;&quot;&#39;</b><br>Type: Joueur<br>Propriétaire: Bob &amp;&lt;&gt;&quot;&#39;",
                marker.detail()
        );
    }

    private BlueMapSettings settings() {
        ClaimMarkerStyle style = new ClaimMarkerStyle(
                new RgbColor(1, 2, 3),
                new RgbColor(4, 5, 6),
                0.25F
        );
        return new BlueMapSettings(
                true,
                "Territoires",
                false,
                2,
                Map.of(
                        LandType.PLAYER, style,
                        LandType.GUILD, style,
                        LandType.SYSTEM, style
                )
        );
    }
}
