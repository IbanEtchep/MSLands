package fr.iban.lands.integration.bluemap;

import fr.iban.lands.integration.claims.ClaimVisualization;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        CountDownLatch secondActionStarted = new CountDownLatch(1);
        delegate.syncChunkAction = () -> {
            firstActionStarted.countDown();
            await(allowFirstFailure);
        };
        delegate.syncChunkRuntimeFailure = new IllegalStateException("BlueMap unavailable");
        delegate.syncLandAction = secondActionStarted::countDown;
        BlueMapFailureCircuitBreaker breaker =
                new BlueMapFailureCircuitBreaker(loggerWith(new RecordingHandler()));
        FaultTolerantClaimVisualization visualization =
                new FaultTolerantClaimVisualization(delegate, breaker);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> visualization.syncChunk(null));
            assertTrue(firstActionStarted.await(1, TimeUnit.SECONDS));
            var second = executor.submit(() -> visualization.syncLand(null));

            assertFalse(secondActionStarted.await(200, TimeUnit.MILLISECONDS));

            allowFirstFailure.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertTrue(breaker.isOpen());
            assertEquals(0, delegate.syncLandCalls);
        } finally {
            executor.shutdownNow();
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
