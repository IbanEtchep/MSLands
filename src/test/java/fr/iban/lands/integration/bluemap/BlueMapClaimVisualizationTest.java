package fr.iban.lands.integration.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import fr.iban.lands.integration.claims.ClaimMarkerSynchronizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BlueMapClaimVisualizationTest {
    @Test
    void synchronousEnableRegistrationAttachesWithoutRebuildingThenLaterEnableRebuilds() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        BlueMapAPI api = mock(BlueMapAPI.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        listeners.enableApiOnRegistration = api;

        BlueMapClaimVisualization visualization = new BlueMapClaimVisualization(
                sink, synchronizer, listeners, newBreaker()
        );

        verify(sink).attach(api);
        verify(synchronizer, never()).rebuild();

        listeners.onEnable.accept(api);

        verify(sink, times(2)).attach(api);
        verify(synchronizer).rebuild();
        visualization.close();
    }

    @Test
    void containsEnableFailureAndSkipsLaterEnableCallbacks() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        BlueMapFailureCircuitBreaker breaker = newBreaker();
        BlueMapClaimVisualization visualization =
                new BlueMapClaimVisualization(sink, synchronizer, listeners, breaker);
        BlueMapAPI api = mock(BlueMapAPI.class);

        doThrow(new IllegalStateException("BlueMap unavailable"))
                .when(sink).attach(api);

        assertDoesNotThrow(() -> listeners.onEnable.accept(api));
        assertDoesNotThrow(() -> listeners.onEnable.accept(api));

        assertTrue(breaker.isOpen());
        verify(sink, times(1)).attach(api);
        verify(synchronizer, never()).rebuild();
        visualization.close();
    }

    @Test
    void attemptsDisableAfterCircuitOpensAndContainsDisableFailure() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        BlueMapFailureCircuitBreaker breaker = newBreaker();
        BlueMapClaimVisualization visualization =
                new BlueMapClaimVisualization(sink, synchronizer, listeners, breaker);
        BlueMapAPI api = mock(BlueMapAPI.class);

        doThrow(new IllegalStateException("BlueMap unavailable"))
                .when(sink).attach(api);
        doThrow(new IllegalStateException("BlueMap cleanup unavailable"))
                .when(sink).detach(api);

        listeners.onEnable.accept(api);
        assertDoesNotThrow(() -> listeners.onDisable.accept(api));

        assertTrue(breaker.isOpen());
        verify(sink).detach(api);
        visualization.close();
    }

    @Test
    void rollsBackEnableRegistrationWhenDisableRegistrationFails() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        RuntimeException failure = new IllegalStateException("disable registration failed");
        listeners.onDisableFailure = failure;

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new BlueMapClaimVisualization(
                        sink, synchronizer, listeners, newBreaker()
                ));

        assertSame(failure, thrown);
        assertEquals(1, listeners.unregistered.size());
        assertSame(listeners.onEnable, listeners.unregistered.getFirst());
    }

    @Test
    void suppressesEnableRollbackFailureOnDisableRegistrationFailure() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        RuntimeException failure = new IllegalStateException("disable registration failed");
        RuntimeException rollbackFailure = new IllegalStateException("enable rollback failed");
        listeners.onDisableFailure = failure;
        listeners.unregisterFailure = rollbackFailure;

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new BlueMapClaimVisualization(
                        sink, synchronizer, listeners, newBreaker()
                ));

        assertSame(failure, thrown);
        assertArrayEquals(new Throwable[]{rollbackFailure}, thrown.getSuppressed());
    }

    @Test
    void rethrowsRegistrationFailureWhenRollbackThrowsTheSameInstance() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        RuntimeException failure = new IllegalStateException("registration failed");
        listeners.onDisableFailure = failure;
        listeners.unregisterFailure = failure;

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new BlueMapClaimVisualization(
                        sink, synchronizer, listeners, newBreaker()
                ));

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void continuesCloseCleanupAfterEnableUnregisterFailure() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        listeners.unregisterFailure = new IllegalStateException("enable unregister failed");
        BlueMapClaimVisualization visualization = new BlueMapClaimVisualization(
                sink, synchronizer, listeners, newBreaker()
        );

        assertDoesNotThrow(visualization::close);

        assertAll(
                () -> assertEquals(2, listeners.unregistered.size()),
                () -> assertSame(listeners.onEnable, listeners.unregistered.get(0)),
                () -> assertSame(listeners.onDisable, listeners.unregistered.get(1))
        );
        verify(synchronizer).close();
    }

    @Test
    void letsFatalJvmErrorsEscapeEnableCallbacks() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        BlueMapFailureCircuitBreaker breaker = newBreaker();
        BlueMapClaimVisualization visualization = new BlueMapClaimVisualization(
                sink, synchronizer, listeners, breaker
        );
        BlueMapAPI api = mock(BlueMapAPI.class);

        doThrow(new OutOfMemoryError("fatal")).when(sink).attach(api);

        assertThrows(OutOfMemoryError.class, () -> listeners.onEnable.accept(api));
        assertFalse(breaker.isOpen());
        visualization.close();
    }

    @Test
    void ownsStableEnableAndDisableCallbacksUntilClose() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        BlueMapClaimVisualization visualization = new BlueMapClaimVisualization(
                sink, synchronizer, listeners, newBreaker()
        );
        BlueMapAPI api = mock(BlueMapAPI.class);

        listeners.onEnable.accept(api);
        listeners.onDisable.accept(api);
        visualization.close();

        var lifecycleOrder = inOrder(sink, synchronizer);
        lifecycleOrder.verify(sink).attach(api);
        lifecycleOrder.verify(synchronizer).rebuild();
        lifecycleOrder.verify(sink).detach(api);
        lifecycleOrder.verify(synchronizer).close();
        assertAll(
                () -> assertEquals(2, listeners.unregistered.size()),
                () -> assertSame(listeners.onEnable, listeners.unregistered.get(0)),
                () -> assertSame(listeners.onDisable, listeners.unregistered.get(1))
        );
    }

    private static BlueMapFailureCircuitBreaker newBreaker() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new BlueMapFailureCircuitBreaker(logger);
    }

    private static final class RecordingListenerRegistry
            implements BlueMapClaimVisualization.ListenerRegistry {
        private Consumer<BlueMapAPI> onEnable;
        private Consumer<BlueMapAPI> onDisable;
        private final List<Consumer<BlueMapAPI>> unregistered = new ArrayList<>();
        private RuntimeException onDisableFailure;
        private RuntimeException unregisterFailure;
        private BlueMapAPI enableApiOnRegistration;

        @Override
        public void onEnable(Consumer<BlueMapAPI> listener) {
            onEnable = listener;
            if (enableApiOnRegistration != null) {
                listener.accept(enableApiOnRegistration);
            }
        }

        @Override
        public void onDisable(Consumer<BlueMapAPI> listener) {
            if (onDisableFailure != null) {
                throw onDisableFailure;
            }
            onDisable = listener;
        }

        @Override
        public void unregister(Consumer<BlueMapAPI> listener) {
            unregistered.add(listener);
            if (unregisterFailure != null && unregistered.size() == 1) {
                throw unregisterFailure;
            }
        }
    }
}
