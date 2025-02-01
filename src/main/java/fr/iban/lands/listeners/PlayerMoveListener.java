package fr.iban.lands.listeners;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.events.PlayerLandEnterEvent;
import fr.iban.lands.model.land.Land;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class PlayerMoveListener implements Listener {

    private LandRepository landRepository;
    private LandsPlugin plugin;

    public PlayerMoveListener(LandsPlugin plugin) {
        this.landRepository = plugin.getLandRepository();
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        final Location from = event.getFrom();
        final Location to = event.getTo();

        int x = Math.abs(from.getBlockX() - to.getBlockX());
        int y = Math.abs(from.getBlockY() - to.getBlockY());
        int z = Math.abs(from.getBlockZ() - to.getBlockZ());

        if (x == 0 && y == 0 && z == 0) return;

        Land landFrom = landRepository.getLandAt(from);
        Land landTo = landRepository.getLandAt(to);

        PlayerLandEnterEvent enter = new PlayerLandEnterEvent(player, landFrom, landTo);

        Bukkit.getPluginManager().callEvent(enter);

        if (enter.isCancelled()) {
            player.teleportAsync(from);
        }
    }
}
