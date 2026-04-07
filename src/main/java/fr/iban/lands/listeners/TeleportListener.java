package fr.iban.lands.listeners;

import fr.iban.bukkitcore.event.PlayerPreTeleportEvent;
import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.events.PlayerLandEnterEvent;
import fr.iban.lands.model.land.Land;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class TeleportListener implements Listener {

    private final LandRepository landRepository;

    public TeleportListener(LandsPlugin plugin) {
        this.landRepository = plugin.getLandRepository();
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        Land landTo = landRepository.getLandAt(to);
        Land landFrom = landRepository.getLandAt(from);

        Player player = event.getPlayer();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();

        if (cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT
                && !landTo.isBypassing(player, Action.CHORUS_TELEPORT)) {
            event.setCancelled(true);
            return;
        }

        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && !landTo.isBypassing(player, Action.ENDER_PEARL_TELEPORT)) {
            event.setCancelled(true);
            return;
        }

        PlayerLandEnterEvent enter = new PlayerLandEnterEvent(player, landFrom, landTo);
        Bukkit.getPluginManager().callEvent(enter);
        if (enter.isCancelled()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCoreTeleport(PlayerPreTeleportEvent event) {
        Player player = event.getPlayer();

        if (player == null) return;

        Land land = landRepository.getLandAt(player.getLocation());

        if (land.hasFlag(Flag.INSTANT_TELEPORT)) {
            event.setDelay(0);
        }
    }
}
