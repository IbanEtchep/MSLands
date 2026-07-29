package fr.iban.lands.integration.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.integration.claims.BlueMapSettings;
import fr.iban.lands.integration.claims.ClaimMarkerDescriptor;
import fr.iban.lands.integration.claims.ClaimMarkerStyle;
import fr.iban.lands.integration.claims.RgbColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlueMapMarkerSinkTest {

    private BlueMapAPI api;
    private BlueMapMapResolver resolver;
    private Logger logger;
    private List<LogRecord> records;

    @BeforeEach
    void setUp() {
        api = mock(BlueMapAPI.class);
        resolver = mock(BlueMapMapResolver.class);
        records = new ArrayList<>();
        logger = Logger.getLogger(getClass().getName() + "." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @Test
    void putCreatesConfiguredMarkerSetAndMarkerOnEveryResolvedMap() {
        Map<String, MarkerSet> firstSets = new HashMap<>();
        Map<String, MarkerSet> secondSets = new HashMap<>();
        BlueMapMap first = map("surface", firstSets);
        BlueMapMap second = map("caves", secondSets);
        when(resolver.resolve(api, "world")).thenReturn(List.of(first, second));
        BlueMapMarkerSink sink = sink(true);

        sink.attach(api);
        sink.put(descriptor("chunk:2:-3"));

        MarkerSet firstSet = firstSets.get("mslands-claims");
        MarkerSet secondSet = secondSets.get("mslands-claims");
        assertAll(
                () -> assertNotNull(firstSet),
                () -> assertNotNull(secondSet),
                () -> assertEquals("Territoires", firstSet.getLabel()),
                () -> assertTrue(firstSet.isToggleable()),
                () -> assertTrue(firstSet.isDefaultHidden()),
                () -> assertNotNull(firstSet.get("chunk:2:-3")),
                () -> assertNotNull(secondSet.get("chunk:2:-3"))
        );
    }

    @Test
    void removeDeletesMarkerFromEveryResolvedMap() {
        Map<String, MarkerSet> firstSets = new HashMap<>();
        Map<String, MarkerSet> secondSets = new HashMap<>();
        BlueMapMap first = map("surface", firstSets);
        BlueMapMap second = map("caves", secondSets);
        when(resolver.resolve(api, "world")).thenReturn(List.of(first, second));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);
        sink.put(descriptor("chunk:2:-3"));

        sink.remove("world", "chunk:2:-3");

        assertAll(
                () -> assertFalse(firstSets.get("mslands-claims").getMarkers().containsKey("chunk:2:-3")),
                () -> assertFalse(secondSets.get("mslands-claims").getMarkers().containsKey("chunk:2:-3"))
        );
    }

    @Test
    void clearRemovesOnlyTheClaimsMarkerSetFromActiveMaps() {
        Map<String, MarkerSet> firstSets = new HashMap<>();
        Map<String, MarkerSet> secondSets = new HashMap<>();
        MarkerSet unrelated = MarkerSet.builder().label("Other").build();
        firstSets.put("other", unrelated);
        secondSets.put("other", unrelated);
        BlueMapMap first = map("surface", firstSets);
        BlueMapMap second = map("caves", secondSets);
        when(resolver.resolve(api, "world")).thenReturn(List.of(first, second));
        when(api.getMaps()).thenReturn(List.of(first, second));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);
        sink.put(descriptor("chunk:2:-3"));

        sink.clear();

        assertAll(
                () -> assertFalse(firstSets.containsKey("mslands-claims")),
                () -> assertFalse(secondSets.containsKey("mslands-claims")),
                () -> assertSame(unrelated, firstSets.get("other")),
                () -> assertSame(unrelated, secondSets.get("other"))
        );
    }

    @Test
    void unresolvedWorldProducesNoMarkerAndOneWarning() {
        when(resolver.resolve(api, "missing")).thenReturn(List.of());
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        sink.put(descriptor("chunk:2:-3", "missing"));

        assertAll(
                () -> assertEquals(1, records.size()),
                () -> assertEquals(Level.WARNING, records.getFirst().getLevel()),
                () -> assertTrue(records.getFirst().getMessage().contains("missing")),
                () -> assertTrue(records.getFirst().getMessage().contains("chunk:2:-3"))
        );
    }

    @Test
    void failingMapDoesNotPreventUpdatingOtherResolvedMaps() {
        BlueMapMap failing = mock(BlueMapMap.class);
        when(failing.getId()).thenReturn("broken");
        when(failing.getMarkerSets()).thenThrow(new IllegalStateException("unavailable"));
        Map<String, MarkerSet> healthySets = new HashMap<>();
        BlueMapMap healthy = map("surface", healthySets);
        when(resolver.resolve(api, "world")).thenReturn(List.of(failing, healthy));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        sink.put(descriptor("chunk:2:-3"));

        assertAll(
                () -> assertNotNull(healthySets.get("mslands-claims").get("chunk:2:-3")),
                () -> assertEquals(1, records.size()),
                () -> assertTrue(records.getFirst().getMessage().contains("broken")),
                () -> assertTrue(records.getFirst().getMessage().contains("world")),
                () -> assertTrue(records.getFirst().getMessage().contains("chunk:2:-3")),
                () -> assertEquals(IllegalStateException.class, records.getFirst().getThrown().getClass())
        );
    }

    @Test
    void detachMakesLaterWritesNoOps() {
        Map<String, MarkerSet> markerSets = new HashMap<>();
        BlueMapMap map = map("surface", markerSets);
        when(resolver.resolve(api, "world")).thenReturn(List.of(map));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);
        sink.put(descriptor("chunk:2:-3"));

        sink.detach(api);
        sink.put(descriptor("chunk:3:-3"));
        sink.remove("world", "chunk:2:-3");
        sink.clear();

        assertAll(
                () -> assertTrue(markerSets.get("mslands-claims").getMarkers().containsKey("chunk:2:-3")),
                () -> assertFalse(markerSets.get("mslands-claims").getMarkers().containsKey("chunk:3:-3"))
        );
    }

    private BlueMapMarkerSink sink(boolean defaultHidden) {
        ClaimMarkerStyle style = new ClaimMarkerStyle(
                new RgbColor(52, 152, 219),
                new RgbColor(52, 152, 219),
                0.25F
        );
        BlueMapSettings settings = new BlueMapSettings(
                true,
                "Territoires",
                defaultHidden,
                2,
                Map.of(LandType.PLAYER, style)
        );
        return new BlueMapMarkerSink(
                settings,
                new BlueMapShapeMarkerFactory(settings.lineWidth()),
                resolver,
                logger
        );
    }

    private BlueMapMap map(String id, Map<String, MarkerSet> markerSets) {
        BlueMapMap map = mock(BlueMapMap.class);
        when(map.getId()).thenReturn(id);
        when(map.getMarkerSets()).thenReturn(markerSets);
        return map;
    }

    private ClaimMarkerDescriptor descriptor(String id) {
        return descriptor(id, "world");
    }

    private ClaimMarkerDescriptor descriptor(String id, String world) {
        return new ClaimMarkerDescriptor(
                id,
                world,
                32,
                -48,
                48,
                -32,
                "Maison",
                "<b>Maison</b>",
                new ClaimMarkerStyle(
                        new RgbColor(52, 152, 219),
                        new RgbColor(46, 204, 113),
                        0.35F
                )
        );
    }
}
