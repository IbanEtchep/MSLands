package fr.iban.lands.integration.claims;

import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;

public interface ClaimVisualization extends AutoCloseable {

    ClaimVisualization NO_OP = new ClaimVisualization() {
        @Override
        public void rebuild() {
        }

        @Override
        public void syncChunk(SChunk chunk) {
        }

        @Override
        public void syncLand(Land land) {
        }

        @Override
        public void close() {
        }
    };

    void rebuild();

    void syncChunk(SChunk chunk);

    void syncLand(Land land);

    @Override
    void close();

    static ClaimVisualization noop() {
        return NO_OP;
    }
}
