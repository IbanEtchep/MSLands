package fr.iban.lands.listeners;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Action;
import fr.iban.lands.model.land.Land;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.PortalCreateEvent.CreateReason;

public class PortalListeners implements Listener {

    private final LandRepository landRepository;

    public PortalListeners(LandsPlugin plugin) {
        this.landRepository = plugin.getLandRepository();
    }

    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getEntity() instanceof Player player) {

            if (event.getReason() == CreateReason.NETHER_PAIR) {
                for (BlockState block : event.getBlocks()) {
                    Land land = landRepository.getLandAt(block.getLocation());

                    if (!land.isBypassing(player, Action.BLOCK_PLACE)) {
                        event.setCancelled(true);
                        LandsPlugin.getInstance().getMessages().send(player, "portal.cancelled");
                        return;
                    }
                }
            }
        }
    }
}
