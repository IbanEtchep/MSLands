package fr.iban.lands.integration.bluemap;

import fr.iban.lands.integration.claims.ClaimVisualization;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;

import java.util.Objects;

public final class FaultTolerantClaimVisualization implements ClaimVisualization {

    private final ClaimVisualization delegate;
    private final BlueMapFailureCircuitBreaker breaker;

    public FaultTolerantClaimVisualization(
            ClaimVisualization delegate,
            BlueMapFailureCircuitBreaker breaker
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.breaker = Objects.requireNonNull(breaker);
    }

    @Override
    public void rebuild() {
        breaker.execute("rebuild", delegate::rebuild);
    }

    @Override
    public void syncChunk(SChunk chunk) {
        breaker.execute("syncChunk", () -> delegate.syncChunk(chunk));
    }

    @Override
    public void syncLand(Land land) {
        breaker.execute("syncLand", () -> delegate.syncLand(land));
    }

    @Override
    public void close() {
        breaker.cleanup("close", delegate::close);
    }
}
