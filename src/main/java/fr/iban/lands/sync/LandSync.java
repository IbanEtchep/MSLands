package fr.iban.lands.sync;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.service.LandRepositoryImpl;
import net.artisanmc.modules.api.network.NetworkApi;
import net.artisanmc.modules.api.network.NetworkMessage;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The lands kept the same on every server of the network, through Artisan's messages. Once a
 * change is saved, the other servers get the land's id; each reads that land again from the
 * database, on its database thread (after the writes it queued itself), then applies it on the
 * global thread. Chunks are not sent: a server only holds its own, read at start. Limits and chunk
 * counts are read from the database each time, so they need nothing.
 *
 * <p>MSLands synchronised nothing: a trust added on one server was seen by the others at their next
 * restart.
 */
public final class LandSync implements LandRepositoryImpl.LandChanges {

    /** Payload: the id of a land created, changed or deleted. */
    public static final String LAND = "lands:land";
    /** Payload: empty. The lands covering whole worlds changed. */
    public static final String WORLDS = "lands:worlds";

    private final LandsPlugin plugin;
    private final LandRepositoryImpl repository;
    private final NetworkApi network;
    private final Logger logger;

    public LandSync(LandsPlugin plugin, LandRepositoryImpl repository, NetworkApi network) {
        this.plugin = plugin;
        this.repository = repository;
        this.network = network;
        this.logger = plugin.getLogger();
    }

    /** Listens to the other servers and tells them this one's changes. */
    public void start() {
        network.subscribe(plugin, LAND, message -> read(message, () -> {
            UUID id = UUID.fromString(message.getPayload());
            LandRepositoryImpl.Fresh fresh = repository.read(id);
            plugin.getScheduler().runNextTick(task -> repository.apply(fresh));
        }));
        network.subscribe(plugin, WORLDS, message -> read(message, () -> {
            Map<String, UUID> defaults = repository.readWorldDefaults();
            plugin.getScheduler().runNextTick(task -> repository.applyWorldDefaults(defaults));
        }));
        repository.setChanges(this);
    }

    @Override
    public void landChanged(UUID id) {
        publish(LAND, id.toString());
    }

    @Override
    public void worldsChanged() {
        publish(WORLDS, "");
    }

    private void publish(String channel, String payload) {
        try {
            network.publish(channel, payload);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "The other servers were not told about a land change (" + channel + " "
                    + payload + "): they see it at their next restart.", e);
        }
    }

    private void read(NetworkMessage message, Runnable read) {
        plugin.runAsyncQueued(() -> {
            try {
                read.run();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Land change from " + message.getSourceServer() + " not applied ("
                        + message.getChannel() + " " + message.getPayload() + ")", e);
            }
        });
    }
}
