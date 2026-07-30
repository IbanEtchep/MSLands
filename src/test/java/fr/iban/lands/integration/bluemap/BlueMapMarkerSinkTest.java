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
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void putAttemptsEveryResolvedMapThenThrowsFirstContextualFailure() {
        IllegalStateException firstFailure = new IllegalStateException("first unavailable");
        IllegalStateException laterFailure = new IllegalStateException("later unavailable");
        BlueMapMap firstFailing = failingMap("broken-first", firstFailure);
        Map<String, MarkerSet> healthySets = new HashMap<>();
        BlueMapMap healthy = map("surface", healthySets);
        BlueMapMap laterFailing = failingMap("broken-later", laterFailure);
        when(resolver.resolve(api, "world"))
                .thenReturn(List.of(firstFailing, healthy, laterFailing));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> sink.put(descriptor("chunk:2:-3"))
        );

        assertAll(
                () -> assertNotNull(healthySets.get("mslands-claims").get("chunk:2:-3")),
                () -> assertTrue(thrown.getMessage().contains("put")),
                () -> assertTrue(thrown.getMessage().contains("broken-first")),
                () -> assertTrue(thrown.getMessage().contains("world")),
                () -> assertTrue(thrown.getMessage().contains("chunk:2:-3")),
                () -> assertSame(firstFailure, thrown.getCause()),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(laterFailure, thrown.getSuppressed()[0].getCause()),
                () -> assertTrue(records.isEmpty())
        );
    }

    @Test
    void unavailableMapIdDoesNotAbortPutBeforeHealthyMap() {
        IllegalStateException markerFailure = new IllegalStateException("markers unavailable");
        IllegalStateException idFailure = new IllegalStateException("id unavailable");
        BlueMapMap failing = failingMapWithUnavailableId(markerFailure, idFailure);
        Map<String, MarkerSet> healthySets = new HashMap<>();
        BlueMapMap healthy = map("surface", healthySets);
        when(resolver.resolve(api, "world")).thenReturn(List.of(failing, healthy));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> sink.put(descriptor("chunk:2:-3"))
        );

        assertAll(
                () -> assertNotNull(healthySets.get("mslands-claims").get("chunk:2:-3")),
                () -> assertTrue(thrown.getMessage().contains("<unavailable>")),
                () -> assertSame(markerFailure, thrown.getCause()),
                () -> assertEquals(1, markerFailure.getSuppressed().length),
                () -> assertSame(idFailure, markerFailure.getSuppressed()[0]),
                () -> assertTrue(records.isEmpty())
        );
    }

    @Test
    void removeAttemptsEveryResolvedMapThenThrowsFirstContextualFailure() {
        IllegalStateException firstFailure = new IllegalStateException("first unavailable");
        IllegalStateException laterFailure = new IllegalStateException("later unavailable");
        BlueMapMap firstFailing = failingMap("broken-first", firstFailure);
        Map<String, MarkerSet> healthySets = new HashMap<>();
        MarkerSet healthyMarkerSet = MarkerSet.builder().label("Territoires").build();
        healthyMarkerSet.put("chunk:2:-3", mock(de.bluecolored.bluemap.api.markers.Marker.class));
        healthySets.put("mslands-claims", healthyMarkerSet);
        BlueMapMap healthy = map("surface", healthySets);
        BlueMapMap laterFailing = failingMap("broken-later", laterFailure);
        when(resolver.resolve(api, "world"))
                .thenReturn(List.of(firstFailing, healthy, laterFailing));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> sink.remove("world", "chunk:2:-3")
        );

        assertAll(
                () -> assertFalse(healthyMarkerSet.getMarkers().containsKey("chunk:2:-3")),
                () -> assertTrue(thrown.getMessage().contains("remove")),
                () -> assertTrue(thrown.getMessage().contains("broken-first")),
                () -> assertTrue(thrown.getMessage().contains("world")),
                () -> assertTrue(thrown.getMessage().contains("chunk:2:-3")),
                () -> assertSame(firstFailure, thrown.getCause()),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(laterFailure, thrown.getSuppressed()[0].getCause()),
                () -> assertTrue(records.isEmpty())
        );
    }

    @Test
    void sameMapFailureCannotSelfSuppressAndDoesNotAbortRemoveBeforeHealthyMap() {
        IllegalStateException sharedFailure = new IllegalStateException("map unavailable");
        BlueMapMap failing = failingMapWithUnavailableId(sharedFailure, sharedFailure);
        MarkerSet healthyMarkerSet = MarkerSet.builder().label("Territoires").build();
        healthyMarkerSet.put("chunk:2:-3", mock(de.bluecolored.bluemap.api.markers.Marker.class));
        Map<String, MarkerSet> healthySets = new HashMap<>();
        healthySets.put("mslands-claims", healthyMarkerSet);
        BlueMapMap healthy = map("surface", healthySets);
        when(resolver.resolve(api, "world")).thenReturn(List.of(failing, healthy));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> sink.remove("world", "chunk:2:-3")
        );

        assertAll(
                () -> assertFalse(healthyMarkerSet.getMarkers().containsKey("chunk:2:-3")),
                () -> assertTrue(thrown.getMessage().contains("<unavailable>")),
                () -> assertSame(sharedFailure, thrown.getCause()),
                () -> assertEquals(0, sharedFailure.getSuppressed().length),
                () -> assertTrue(records.isEmpty())
        );
    }

    @Test
    void clearAttemptsEveryActiveMapThenThrowsFirstContextualFailure() {
        IllegalStateException firstFailure = new IllegalStateException("first unavailable");
        IllegalStateException laterFailure = new IllegalStateException("later unavailable");
        BlueMapMap firstFailing = failingMap("broken-first", firstFailure);
        Map<String, MarkerSet> healthySets = new HashMap<>();
        healthySets.put("mslands-claims", MarkerSet.builder().label("Territoires").build());
        BlueMapMap healthy = map("surface", healthySets);
        BlueMapMap laterFailing = failingMap("broken-later", laterFailure);
        when(api.getMaps()).thenReturn(List.of(firstFailing, healthy, laterFailing));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        RuntimeException thrown = assertThrows(RuntimeException.class, sink::clear);

        assertAll(
                () -> assertFalse(healthySets.containsKey("mslands-claims")),
                () -> assertTrue(thrown.getMessage().contains("clear")),
                () -> assertTrue(thrown.getMessage().contains("broken-first")),
                () -> assertTrue(thrown.getMessage().contains("mslands-claims")),
                () -> assertSame(firstFailure, thrown.getCause()),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(laterFailure, thrown.getSuppressed()[0].getCause()),
                () -> assertTrue(records.isEmpty())
        );
    }

    @Test
    void unavailableMapIdDoesNotAbortClearBeforeHealthyMap() {
        IllegalStateException markerFailure = new IllegalStateException("markers unavailable");
        IllegalStateException idFailure = new IllegalStateException("id unavailable");
        BlueMapMap failing = failingMapWithUnavailableId(markerFailure, idFailure);
        Map<String, MarkerSet> healthySets = new HashMap<>();
        healthySets.put("mslands-claims", MarkerSet.builder().label("Territoires").build());
        BlueMapMap healthy = map("surface", healthySets);
        when(api.getMaps()).thenReturn(List.of(failing, healthy));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        RuntimeException thrown = assertThrows(RuntimeException.class, sink::clear);

        assertAll(
                () -> assertFalse(healthySets.containsKey("mslands-claims")),
                () -> assertTrue(thrown.getMessage().contains("<unavailable>")),
                () -> assertSame(markerFailure, thrown.getCause()),
                () -> assertEquals(1, markerFailure.getSuppressed().length),
                () -> assertSame(idFailure, markerFailure.getSuppressed()[0]),
                () -> assertTrue(records.isEmpty())
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

    @Test
    void concurrentReplacementAndStaleDetachNeverDiscardNewApi() throws Exception {
        BlueMapAPI oldApi = mock(BlueMapAPI.class);
        BlueMapAPI newApi = mock(BlueMapAPI.class);
        AtomicReference<BlueMapAPI> resolvedApi = new AtomicReference<>();
        BlueMapMap map = map("surface", new HashMap<>());
        BlueMapMapResolver recordingResolver = new BlueMapMapResolver() {
            @Override
            public Collection<BlueMapMap> resolve(BlueMapAPI api, String worldName) {
                resolvedApi.set(api);
                return List.of(map);
            }
        };
        BlueMapMarkerSink sink = sink(false, recordingResolver);
        int iterations = 5_000;
        CyclicBarrier barrier = new CyclicBarrier(3);
        AtomicInteger staleTransitions = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> attaches = executor.submit(() -> repeat(iterations, barrier, () -> sink.attach(newApi)));
            Future<?> detaches = executor.submit(() -> repeat(iterations, barrier, () -> sink.detach(oldApi)));

            for (int iteration = 0; iteration < iterations; iteration++) {
                sink.attach(oldApi);
                resolvedApi.set(null);
                barrier.await();
                barrier.await();
                sink.remove("world", "chunk:2:-3");
                if (resolvedApi.get() != newApi) {
                    staleTransitions.incrementAndGet();
                }
            }

            attaches.get();
            detaches.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, staleTransitions.get());
    }

    @Test
    void closeWaitsForInFlightWriteThenRemovesItsMarker() throws Exception {
        Map<String, MarkerSet> markerSets = new HashMap<>();
        BlueMapMap map = map("surface", markerSets);
        when(api.getMaps()).thenReturn(List.of(map));
        var writeEntered = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        BlueMapMapResolver blockingResolver = new BlueMapMapResolver() {
            @Override
            public Collection<BlueMapMap> resolve(BlueMapAPI ignored, String worldName) {
                writeEntered.countDown();
                await(releaseWrite);
                return List.of(map);
            }
        };
        BlueMapMarkerSink sink = sink(false, blockingResolver);
        sink.attach(api);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> write = executor.submit(() -> sink.put(descriptor("chunk:2:-3")));
            assertTrue(writeEntered.await(1, TimeUnit.SECONDS));
            Future<?> close = executor.submit(sink::close);

            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));

            releaseWrite.countDown();
            write.get(1, TimeUnit.SECONDS);
            close.get(1, TimeUnit.SECONDS);
            assertFalse(markerSets.containsKey("mslands-claims"));
        } finally {
            releaseWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeDiscardsApiEvenWhenCleanupFails() {
        Map<String, MarkerSet> markerSets = new HashMap<>();
        BlueMapMap map = map("surface", markerSets);
        when(resolver.resolve(api, "world")).thenReturn(List.of(map));
        when(api.getMaps()).thenThrow(new IllegalStateException("maps unavailable"));
        BlueMapMarkerSink sink = sink(false);
        sink.attach(api);

        assertThrows(IllegalStateException.class, sink::close);
        sink.put(descriptor("chunk:2:-3"));

        assertTrue(markerSets.isEmpty());
    }

    private BlueMapMarkerSink sink(boolean defaultHidden) {
        return sink(defaultHidden, resolver);
    }

    private BlueMapMarkerSink sink(boolean defaultHidden, BlueMapMapResolver mapResolver) {
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
                mapResolver,
                logger
        );
    }

    private BlueMapMap map(String id, Map<String, MarkerSet> markerSets) {
        BlueMapMap map = mock(BlueMapMap.class);
        when(map.getId()).thenReturn(id);
        when(map.getMarkerSets()).thenReturn(markerSets);
        return map;
    }

    private BlueMapMap failingMap(String id, RuntimeException failure) {
        BlueMapMap map = mock(BlueMapMap.class);
        when(map.getId()).thenReturn(id);
        when(map.getMarkerSets()).thenThrow(failure);
        return map;
    }

    private BlueMapMap failingMapWithUnavailableId(
            RuntimeException markerFailure,
            RuntimeException idFailure
    ) {
        BlueMapMap map = mock(BlueMapMap.class);
        when(map.getMarkerSets()).thenThrow(markerFailure);
        when(map.getId()).thenThrow(idFailure);
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

    private void repeat(int iterations, CyclicBarrier barrier, Runnable action) {
        try {
            for (int iteration = 0; iteration < iterations; iteration++) {
                barrier.await();
                action.run();
                barrier.await();
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }
}
