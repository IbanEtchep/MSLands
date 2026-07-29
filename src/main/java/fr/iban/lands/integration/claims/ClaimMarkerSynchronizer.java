package fr.iban.lands.integration.claims;

import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;

import java.util.List;
import java.util.Objects;

public final class ClaimMarkerSynchronizer implements ClaimVisualization {

    private final String serverName;
    private final ClaimSource source;
    private final ClaimMarkerSink sink;
    private final ClaimMarkerFactory markerFactory;

    public ClaimMarkerSynchronizer(
            String serverName,
            ClaimSource source,
            ClaimMarkerSink sink,
            ClaimMarkerFactory markerFactory
    ) {
        this.serverName = Objects.requireNonNull(serverName);
        this.source = Objects.requireNonNull(source);
        this.sink = Objects.requireNonNull(sink);
        this.markerFactory = Objects.requireNonNull(markerFactory);
    }

    @Override
    public void rebuild() {
        sink.clear();
        List.copyOf(source.claims().keySet()).forEach(this::syncChunk);
    }

    @Override
    public void syncChunk(SChunk chunk) {
        if (!serverName.equals(chunk.getServer())) {
            return;
        }

        Land land = source.landAt(chunk);
        if (source.isWilderness(land)) {
            sink.remove(chunk.getWorld(), markerFactory.markerId(chunk));
            return;
        }

        markerFactory.create(chunk, land).ifPresentOrElse(
                sink::put,
                () -> sink.remove(chunk.getWorld(), markerFactory.markerId(chunk))
        );
    }

    @Override
    public void syncLand(Land land) {
        List.copyOf(source.chunks(land)).forEach(this::syncChunk);
    }

    @Override
    public void close() {
        sink.close();
    }
}
