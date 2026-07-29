package fr.iban.lands.integration.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import fr.iban.lands.integration.claims.BlueMapSettings;
import fr.iban.lands.integration.claims.ClaimMarkerDescriptor;
import fr.iban.lands.integration.claims.ClaimMarkerSink;

import java.util.Collection;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BlueMapMarkerSink implements ClaimMarkerSink {

    static final String MARKER_SET_ID = "mslands-claims";

    private final BlueMapSettings settings;
    private final BlueMapShapeMarkerFactory markerFactory;
    private final BlueMapMapResolver mapResolver;
    private final Logger logger;
    private volatile BlueMapAPI activeApi;
    private boolean closed;

    public BlueMapMarkerSink(
            BlueMapSettings settings,
            BlueMapShapeMarkerFactory markerFactory,
            BlueMapMapResolver mapResolver,
            Logger logger
    ) {
        this.settings = Objects.requireNonNull(settings);
        this.markerFactory = Objects.requireNonNull(markerFactory);
        this.mapResolver = Objects.requireNonNull(mapResolver);
        this.logger = Objects.requireNonNull(logger);
    }

    public synchronized void attach(BlueMapAPI api) {
        if (closed) {
            return;
        }
        activeApi = Objects.requireNonNull(api);
    }

    public synchronized void detach(BlueMapAPI api) {
        if (activeApi == api) {
            activeApi = null;
        }
    }

    @Override
    public synchronized void clear() {
        BlueMapAPI api = activeApi;
        if (api == null) {
            return;
        }

        clear(api);
    }

    private void clear(BlueMapAPI api) {
        for (BlueMapMap map : api.getMaps()) {
            try {
                map.getMarkerSets().remove(MARKER_SET_ID);
            } catch (RuntimeException exception) {
                logger.log(
                        Level.WARNING,
                        "Failed to clear BlueMap marker set '" + MARKER_SET_ID
                                + "' from map '" + map.getId() + "'.",
                        exception
                );
            }
        }
    }

    @Override
    public synchronized void put(ClaimMarkerDescriptor marker) {
        BlueMapAPI api = activeApi;
        if (api == null) {
            return;
        }

        Collection<BlueMapMap> maps = mapResolver.resolve(api, marker.world());
        if (maps.isEmpty()) {
            warnUnresolved(marker.world(), marker.id());
            return;
        }

        for (BlueMapMap map : maps) {
            try {
                markerSet(map).put(marker.id(), markerFactory.create(marker));
            } catch (RuntimeException exception) {
                warnMutation("put", map, marker.world(), marker.id(), exception);
            }
        }
    }

    @Override
    public synchronized void remove(String world, String markerId) {
        BlueMapAPI api = activeApi;
        if (api == null) {
            return;
        }

        Collection<BlueMapMap> maps = mapResolver.resolve(api, world);
        if (maps.isEmpty()) {
            warnUnresolved(world, markerId);
            return;
        }

        for (BlueMapMap map : maps) {
            try {
                MarkerSet markerSet = map.getMarkerSets().get(MARKER_SET_ID);
                if (markerSet != null) {
                    markerSet.remove(markerId);
                }
            } catch (RuntimeException exception) {
                warnMutation("remove", map, world, markerId, exception);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        BlueMapAPI api = activeApi;
        activeApi = null;
        if (api != null) {
            clear(api);
        }
    }

    private MarkerSet markerSet(BlueMapMap map) {
        return map.getMarkerSets().computeIfAbsent(
                MARKER_SET_ID,
                ignored -> MarkerSet.builder()
                        .label(settings.markerSetLabel())
                        .toggleable(true)
                        .defaultHidden(settings.defaultHidden())
                        .build()
        );
    }

    private void warnUnresolved(String world, String markerId) {
        logger.warning(
                "Could not resolve BlueMap maps for world '" + world
                        + "' while updating marker '" + markerId + "'."
        );
    }

    private void warnMutation(
            String operation,
            BlueMapMap map,
            String world,
            String markerId,
            RuntimeException exception
    ) {
        logger.log(
                Level.WARNING,
                "Failed to " + operation + " BlueMap marker '" + markerId
                        + "' on map '" + map.getId() + "' for world '" + world + "'.",
                exception
        );
    }
}
