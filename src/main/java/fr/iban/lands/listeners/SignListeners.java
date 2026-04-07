package fr.iban.lands.listeners;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Action;
import fr.iban.lands.model.land.Land;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

public class SignListeners implements Listener {

    private final LandRepository landRepository;

    public SignListeners(LandsPlugin plugin) {
        this.landRepository = plugin.getLandRepository();
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Land land = landRepository.getLandAt(event.getBlock().getLocation());
        if (!land.isBypassing(player, Action.SIGN_EDIT)) {
            event.setCancelled(true);
        }
    }
}
