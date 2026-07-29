package fr.iban.lands.integration.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import fr.iban.lands.integration.claims.ClaimMarkerSynchronizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class BlueMapClaimVisualizationTest {

    @Test
    void ownsStableEnableAndDisableCallbacksUntilClose() {
        BlueMapMarkerSink sink = mock(BlueMapMarkerSink.class);
        ClaimMarkerSynchronizer synchronizer = mock(ClaimMarkerSynchronizer.class);
        RecordingListenerRegistry listeners = new RecordingListenerRegistry();
        BlueMapClaimVisualization visualization =
                new BlueMapClaimVisualization(sink, synchronizer, listeners);
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

    private static final class RecordingListenerRegistry
            implements BlueMapClaimVisualization.ListenerRegistry {

        private Consumer<BlueMapAPI> onEnable;
        private Consumer<BlueMapAPI> onDisable;
        private final List<Consumer<BlueMapAPI>> unregistered = new ArrayList<>();

        @Override
        public void onEnable(Consumer<BlueMapAPI> listener) {
            onEnable = listener;
        }

        @Override
        public void onDisable(Consumer<BlueMapAPI> listener) {
            onDisable = listener;
        }

        @Override
        public void unregister(Consumer<BlueMapAPI> listener) {
            unregistered.add(listener);
        }
    }
}
