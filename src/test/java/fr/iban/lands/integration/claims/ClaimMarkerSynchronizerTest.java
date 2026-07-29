package fr.iban.lands.integration.claims;

import fr.iban.lands.enums.LandType;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.PlayerLand;
import fr.iban.lands.model.land.SystemLand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMarkerSynchronizerTest {

    private final SChunk chunk = new SChunk("survival", "world", 4, -2);
    private final Land wilderness = new SystemLand(UUID.randomUUID(), "Wilderness");
    private final Land playerLand = new PlayerLand(UUID.randomUUID(), UUID.randomUUID(), "Maison");

    private FakeClaimSource source;
    private RecordingSink sink;
    private ClaimMarkerSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        source = new FakeClaimSource(wilderness);
        sink = new RecordingSink();
        synchronizer = new ClaimMarkerSynchronizer(
                "survival",
                source,
                sink,
                new ClaimMarkerFactory(settings(), land -> "Alice")
        );
    }

    @Test
    void replacesMarkerAfterSuccessfulClaim() {
        source.put(chunk, playerLand);

        synchronizer.syncChunk(chunk);

        assertEquals(Set.of("world/chunk:4:-2"), sink.putKeys());
        assertTrue(sink.removedKeys().isEmpty());
    }

    @Test
    void removesMarkerWhenChunkIsWilderness() {
        source.put(chunk, wilderness);

        synchronizer.syncChunk(chunk);

        assertEquals(Set.of("world/chunk:4:-2"), sink.removedKeys());
        assertTrue(sink.putKeys().isEmpty());
    }

    @Test
    void rebuildClearsBeforeSynchronizingSupportedLocalClaims() {
        SChunk remoteChunk = new SChunk("creative", "world", 1, 1);
        source.put(chunk, playerLand);
        source.put(remoteChunk, playerLand);

        synchronizer.rebuild();

        assertEquals(List.of("clear", "put:world/chunk:4:-2"), sink.events());
    }

    @Test
    void syncLandRefreshesEveryClaimedChunkAfterRename() {
        SChunk otherChunk = new SChunk("survival", "world_nether", -1, 3);
        source.put(chunk, playerLand);
        source.put(otherChunk, playerLand);

        synchronizer.syncLand(playerLand);

        assertEquals(Set.of("world/chunk:4:-2", "world_nether/chunk:-1:3"), sink.putKeys());
        assertTrue(sink.removedKeys().isEmpty());
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

    private static final class FakeClaimSource implements ClaimSource {

        private final Land wilderness;
        private final Map<SChunk, Land> claims = new LinkedHashMap<>();

        private FakeClaimSource(Land wilderness) {
            this.wilderness = wilderness;
        }

        private void put(SChunk chunk, Land land) {
            claims.put(chunk, land);
        }

        @Override
        public Map<SChunk, Land> claims() {
            return Map.copyOf(claims);
        }

        @Override
        public Land landAt(SChunk chunk) {
            return claims.getOrDefault(chunk, wilderness);
        }

        @Override
        public Collection<SChunk> chunks(Land land) {
            return claims.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(land))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        @Override
        public boolean isWilderness(Land land) {
            return wilderness.equals(land);
        }
    }

    private static final class RecordingSink implements ClaimMarkerSink {

        private final List<String> events = new ArrayList<>();
        private final Set<String> putKeys = new LinkedHashSet<>();
        private final Set<String> removedKeys = new LinkedHashSet<>();

        @Override
        public void clear() {
            events.add("clear");
        }

        @Override
        public void put(ClaimMarkerDescriptor marker) {
            String key = key(marker.world(), marker.id());
            events.add("put:" + key);
            putKeys.add(key);
        }

        @Override
        public void remove(String world, String markerId) {
            String key = key(world, markerId);
            events.add("remove:" + key);
            removedKeys.add(key);
        }

        @Override
        public void close() {
        }

        private List<String> events() {
            return List.copyOf(events);
        }

        private Set<String> putKeys() {
            return Set.copyOf(putKeys);
        }

        private Set<String> removedKeys() {
            return Set.copyOf(removedKeys);
        }

        private String key(String world, String markerId) {
            return world + "/" + markerId;
        }
    }
}
