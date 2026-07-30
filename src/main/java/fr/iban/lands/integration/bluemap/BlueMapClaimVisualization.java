package fr.iban.lands.integration.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import fr.iban.lands.LandsPlugin;
import fr.iban.lands.integration.claims.BlueMapSettings;
import fr.iban.lands.integration.claims.ClaimMarkerFactory;
import fr.iban.lands.integration.claims.ClaimMarkerSynchronizer;
import fr.iban.lands.integration.claims.ClaimOwnerLabelResolver;
import fr.iban.lands.integration.claims.ClaimSource;
import fr.iban.lands.integration.claims.ClaimVisualization;
import fr.iban.lands.integration.claims.RepositoryClaimSource;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.GuildLand;
import fr.iban.lands.model.land.Land;
import org.bukkit.Bukkit;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class BlueMapClaimVisualization implements ClaimVisualization {
    private static final ListenerRegistry BLUE_MAP_LISTENERS = new ListenerRegistry() {
        @Override
        public void onEnable(Consumer<BlueMapAPI> listener) {
            BlueMapAPI.onEnable(listener);
        }

        @Override
        public void onDisable(Consumer<BlueMapAPI> listener) {
            BlueMapAPI.onDisable(listener);
        }

        @Override
        public void unregister(Consumer<BlueMapAPI> listener) {
            BlueMapAPI.unregisterListener(listener);
        }
    };

    private final BlueMapMarkerSink sink;
    private final ClaimMarkerSynchronizer synchronizer;
    private final ListenerRegistry listeners;
    private final BlueMapFailureCircuitBreaker breaker;
    private final Consumer<BlueMapAPI> onEnable;
    private final Consumer<BlueMapAPI> onDisable;

    public BlueMapClaimVisualization(
            BlueMapMarkerSink sink,
            ClaimMarkerSynchronizer synchronizer
    ) {
        this(sink, synchronizer, BLUE_MAP_LISTENERS, defaultBreaker());
    }

    BlueMapClaimVisualization(
            BlueMapMarkerSink sink,
            ClaimMarkerSynchronizer synchronizer,
            ListenerRegistry listeners
    ) {
        this(sink, synchronizer, listeners, defaultBreaker());
    }

    BlueMapClaimVisualization(
            BlueMapMarkerSink sink,
            ClaimMarkerSynchronizer synchronizer,
            ListenerRegistry listeners,
            BlueMapFailureCircuitBreaker breaker
    ) {
        this.sink = Objects.requireNonNull(sink);
        this.synchronizer = Objects.requireNonNull(synchronizer);
        this.listeners = Objects.requireNonNull(listeners);
        this.breaker = Objects.requireNonNull(breaker);
        this.onEnable = api -> breaker.execute("BlueMap enable", () -> {
            sink.attach(api);
            synchronizer.rebuild();
        });
        this.onDisable = api -> breaker.cleanup("BlueMap disable", () -> sink.detach(api));
        listeners.onEnable(onEnable);
        try {
            listeners.onDisable(onDisable);
        } catch (RuntimeException | LinkageError failure) {
            try {
                listeners.unregister(onEnable);
            } catch (RuntimeException | LinkageError rollbackFailure) {
                if (rollbackFailure != failure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    public static ClaimVisualization create(LandsPlugin plugin) {
        BlueMapSettings settings = BlueMapSettings.load(
                plugin.getConfig()::get,
                plugin.getLogger()::warning
        );
        ClaimSource source = new RepositoryClaimSource(plugin.getLandRepository());
        ClaimOwnerLabelResolver ownerLabelResolver = land -> ownerLabel(plugin, land);
        ClaimMarkerFactory descriptorFactory = new ClaimMarkerFactory(settings, ownerLabelResolver);
        BlueMapShapeMarkerFactory shapeMarkerFactory =
                new BlueMapShapeMarkerFactory(settings.lineWidth());
        BlueMapMapResolver mapResolver = new BlueMapMapResolver();
        BlueMapMarkerSink sink = new BlueMapMarkerSink(
                settings,
                shapeMarkerFactory,
                mapResolver,
                plugin.getLogger()
        );
        ClaimMarkerSynchronizer synchronizer = new ClaimMarkerSynchronizer(
                plugin.getServerName(),
                source,
                sink,
                descriptorFactory
        );
        BlueMapFailureCircuitBreaker breaker =
                new BlueMapFailureCircuitBreaker(plugin.getLogger());
        BlueMapClaimVisualization lifecycleOwner = new BlueMapClaimVisualization(
                sink,
                synchronizer,
                BLUE_MAP_LISTENERS,
                breaker
        );
        return new FaultTolerantClaimVisualization(lifecycleOwner, breaker);
    }

    @Override
    public void rebuild() {
        synchronizer.rebuild();
    }

    @Override
    public void syncChunk(SChunk chunk) {
        synchronizer.syncChunk(chunk);
    }

    @Override
    public void syncLand(Land land) {
        synchronizer.syncLand(land);
    }

    @Override
    public void close() {
        breaker.cleanup("BlueMap enable listener unregister", () -> listeners.unregister(onEnable));
        breaker.cleanup("BlueMap disable listener unregister", () -> listeners.unregister(onDisable));
        breaker.cleanup("BlueMap synchronizer close", synchronizer::close);
    }

    private static BlueMapFailureCircuitBreaker defaultBreaker() {
        return new BlueMapFailureCircuitBreaker(
                Logger.getLogger(BlueMapClaimVisualization.class.getName())
        );
    }

    private static String ownerLabel(LandsPlugin plugin, Land land) {
        UUID owner = land.getOwner();
        if (owner == null) {
            return "";
        }
        if (land instanceof GuildLand guildLand) {
            if (plugin.isGuildsHookEnabled()) {
                String guildName = guildLand.getGuildName();
                if (guildName != null) {
                    return guildName;
                }
            }
            return owner.toString();
        }
        String playerName = Bukkit.getOfflinePlayer(owner).getName();
        return playerName == null ? owner.toString() : playerName;
    }

    interface ListenerRegistry {
        void onEnable(Consumer<BlueMapAPI> listener);

        void onDisable(Consumer<BlueMapAPI> listener);

        void unregister(Consumer<BlueMapAPI> listener);
    }
}
