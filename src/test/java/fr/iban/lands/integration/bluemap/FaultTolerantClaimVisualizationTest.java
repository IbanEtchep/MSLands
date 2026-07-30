package fr.iban.lands.integration.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.integration.claims.BlueMapSettings;
import fr.iban.lands.integration.claims.ClaimMarkerDescriptor;
import fr.iban.lands.integration.claims.ClaimMarkerStyle;
import fr.iban.lands.integration.claims.ClaimVisualization;
import fr.iban.lands.integration.claims.RgbColor;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaultTolerantClaimVisualizationTest {

    @Test
    void containsFirstRuntimeFailureLogsOnceAndSkipsLaterSyncChunk() {
        RecordingHandler handler = new RecordingHandler();
        Logger logger = loggerWith(handler);
        RecordingVisualization delegate = new RecordingVisualization();
        delegate.syncChunkRuntimeFailure = new IllegalStateException("BlueMap unavailable");
        BlueMapFailureCircuitBreaker breaker = new BlueMapFailureCircuitBreaker(logger);
        FaultTolerantClaimVisualization visualization =
                new FaultTolerantClaimVisualization(delegate, breaker);

        assertDoesNotThrow(() -> visualization.syncChunk(null));
        assertDoesNotThrow(() -> visualization.syncChunk(null));

        assertTrue(breaker.isOpen());
        assertEquals(1, delegate.syncChunkCalls);
        assertEquals(1, handler.records.size());
        assertEquals(Level.SEVERE, handler.records.getFirst().getLevel());
        assertEquals(
                "BlueMap integration failed during syncChunk and is disabled until restart.",
                handler.records.getFirst().getMessage()
        );
    }

    @Test
    void containsLinkageErrorsButLetsFatalErrorsEscape() {
        RecordingVisualization linkageDelegate = new RecordingVisualization();
        linkageDelegate.syncChunkFailure = new NoClassDefFoundError("BlueMapAPI");
        BlueMapFailureCircuitBreaker linkageBreaker =
                new BlueMapFailureCircuitBreaker(loggerWith(new RecordingHandler()));
        FaultTolerantClaimVisualization linkageVisualization =
                new FaultTolerantClaimVisualization(linkageDelegate, linkageBreaker);

        assertDoesNotThrow(() -> linkageVisualization.syncChunk(null));
        assertTrue(linkageBreaker.isOpen());

        RecordingVisualization fatalDelegate = new RecordingVisualization();
        fatalDelegate.syncChunkFailure = new OutOfMemoryError("fatal");
        BlueMapFailureCircuitBreaker fatalBreaker =
                new BlueMapFailureCircuitBreaker(loggerWith(new RecordingHandler()));
        FaultTolerantClaimVisualization fatalVisualization =
                new FaultTolerantClaimVisualization(fatalDelegate, fatalBreaker);

        assertThrows(OutOfMemoryError.class, () -> fatalVisualization.syncChunk(null));
        assertFalse(fatalBreaker.isOpen());
    }

    @Test
    void attemptsCloseAfterCircuitOpensAndContainsItsSupportedFailure() {
        RecordingVisualization delegate = new RecordingVisualization();
        delegate.syncChunkRuntimeFailure = new IllegalStateException("BlueMap unavailable");
        delegate.closeFailure = new IllegalStateException("BlueMap cleanup unavailable");
        RecordingHandler handler = new RecordingHandler();
        BlueMapFailureCircuitBreaker breaker =
                new BlueMapFailureCircuitBreaker(loggerWith(handler));
        FaultTolerantClaimVisualization visualization =
                new FaultTolerantClaimVisualization(delegate, breaker);

        visualization.syncChunk(null);

        assertDoesNotThrow(visualization::close);
        assertEquals(1, delegate.closeCalls);
        assertEquals(1, handler.records.size());
    }

    @Test
    void preventsQueuedSynchronizationFromStartingAfterAnotherThreadOpensCircuit()
            throws Exception {
        RecordingVisualization delegate = new RecordingVisualization();
        CountDownLatch firstActionStarted = new CountDownLatch(1);
        CountDownLatch allowFirstFailure = new CountDownLatch(1);
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        CountDownLatch secondActionStarted = new CountDownLatch(1);
        CountDownLatch allowSecondActionToComplete = new CountDownLatch(1);
        delegate.syncChunkAction = () -> {
            firstActionStarted.countDown();
            await(allowFirstFailure);
        };
        delegate.syncChunkRuntimeFailure = new IllegalStateException("BlueMap unavailable");
        delegate.syncLandAction = () -> {
            secondActionStarted.countDown();
            await(allowSecondActionToComplete);
        };
        BlueMapFailureCircuitBreaker breaker =
                new BlueMapFailureCircuitBreaker(loggerWith(new RecordingHandler()));
        FaultTolerantClaimVisualization visualization =
                new FaultTolerantClaimVisualization(delegate, breaker);
        Thread first = new Thread(() -> visualization.syncChunk(null));
        Thread second = new Thread(() -> {
            secondAttemptStarted.countDown();
            visualization.syncLand(null);
        });

        try {
            first.start();
            assertTrue(firstActionStarted.await(1, TimeUnit.SECONDS));
            second.start();
            assertTrue(secondAttemptStarted.await(1, TimeUnit.SECONDS));

            awaitAdmissionOutcome(second, secondActionStarted);
            assertEquals(Thread.State.BLOCKED, second.getState());

            allowFirstFailure.countDown();
            first.join(1_000);
            second.join(1_000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(breaker.isOpen());
            assertEquals(0, delegate.syncLandCalls);
        } finally {
            allowFirstFailure.countDown();
            allowSecondActionToComplete.countDown();
            first.join(1_000);
            second.join(1_000);
        }
    }

    @Test
    void routesRebuildAndSyncLandThroughTheCircuitBreaker() {
        RecordingVisualization delegate = new RecordingVisualization();
        delegate.rebuildFailure = new IllegalStateException("BlueMap unavailable");
        BlueMapFailureCircuitBreaker breaker =
                new BlueMapFailureCircuitBreaker(loggerWith(new RecordingHandler()));
        FaultTolerantClaimVisualization visualization =
                new FaultTolerantClaimVisualization(delegate, breaker);

        visualization.rebuild();
        visualization.syncLand(null);

        assertTrue(breaker.isOpen());
        assertEquals(1, delegate.rebuildCalls);
        assertEquals(0, delegate.syncLandCalls);
    }

    @Test
    void sinkFailureOpensSharedBreakerLogsOnceAndSkipsLaterVisualizationCall() {
        RecordingHandler handler = new RecordingHandler();
        Logger logger = loggerWith(handler);
        BlueMapAPI api = mock(BlueMapAPI.class);
        BlueMapMap failingMap = mock(BlueMapMap.class);
        IllegalStateException sinkFailure = new IllegalStateException("map unavailable");
        when(failingMap.getId()).thenReturn("broken");
        when(failingMap.getMarkerSets()).thenThrow(sinkFailure);
        BlueMapMapResolver resolver = mock(BlueMapMapResolver.class);
        when(resolver.resolve(api, "world")).thenReturn(List.of(failingMap));
        BlueMapMarkerSink sink = markerSink(logger, resolver);
        sink.attach(api);
        RecordingVisualization delegate = new RecordingVisualization();
        delegate.syncChunkAction = () -> sink.put(descriptor());
        BlueMapFailureCircuitBreaker breaker = new BlueMapFailureCircuitBreaker(logger);
        FaultTolerantClaimVisualization visualization =
                new FaultTolerantClaimVisualization(delegate, breaker);

        visualization.syncChunk(null);
        visualization.syncLand(null);

        assertTrue(breaker.isOpen());
        assertEquals(1, delegate.syncChunkCalls);
        assertEquals(0, delegate.syncLandCalls);
        assertEquals(1, handler.records.size());
        assertTrue(handler.records.getFirst().getThrown().getMessage().contains("broken"));
        assertTrue(handler.records.getFirst().getThrown().getMessage().contains("world"));
        assertTrue(handler.records.getFirst().getThrown().getMessage().contains("chunk:2:-3"));
    }

    private static BlueMapMarkerSink markerSink(
            Logger logger,
            BlueMapMapResolver resolver
    ) {
        ClaimMarkerStyle style = new ClaimMarkerStyle(
                new RgbColor(52, 152, 219),
                new RgbColor(52, 152, 219),
                0.25F
        );
        BlueMapSettings settings = new BlueMapSettings(
                true,
                "Territoires",
                false,
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

    private static ClaimMarkerDescriptor descriptor() {
        return new ClaimMarkerDescriptor(
                "chunk:2:-3",
                "world",
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

    private static Logger loggerWith(Handler handler) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        return logger;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitAdmissionOutcome(
            Thread worker,
            CountDownLatch delegateActionStarted
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (worker.getState() != Thread.State.BLOCKED
                && delegateActionStarted.getCount() != 0
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(
                worker.getState() == Thread.State.BLOCKED
                        || delegateActionStarted.getCount() == 0,
                "worker did not reach circuit admission"
        );
    }

    private static final class RecordingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

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
    }

    private static final class RecordingVisualization implements ClaimVisualization {

        private int rebuildCalls;
        private int syncChunkCalls;
        private int syncLandCalls;
        private int closeCalls;
        private Error syncChunkFailure;
        private RuntimeException rebuildFailure;
        private RuntimeException syncChunkRuntimeFailure;
        private Runnable syncChunkAction;
        private Runnable syncLandAction;
        private RuntimeException closeFailure;

        @Override
        public void rebuild() {
            rebuildCalls++;
            if (rebuildFailure != null) {
                throw rebuildFailure;
            }
        }

        @Override
        public void syncChunk(SChunk chunk) {
            syncChunkCalls++;
            if (syncChunkAction != null) {
                syncChunkAction.run();
            }
            if (syncChunkFailure != null) {
                throw syncChunkFailure;
            }
            if (syncChunkRuntimeFailure != null) {
                throw syncChunkRuntimeFailure;
            }
        }

        @Override
        public void syncLand(Land land) {
            syncLandCalls++;
            if (syncLandAction != null) {
                syncLandAction.run();
            }
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
