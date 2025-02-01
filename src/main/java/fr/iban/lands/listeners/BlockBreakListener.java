package fr.iban.lands.listeners;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.model.land.Land;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

import java.util.EnumSet;
import java.util.Set;

public class BlockBreakListener implements Listener {

    private final LandsPlugin plugin;
    private final LandRepository landRepository;
    private final Set<Material> cropList = EnumSet.of(
            Material.WHEAT,
            Material.POTATOES,
            Material.CARROTS,
            Material.BEETROOTS,
            Material.NETHER_WART
    );

    public BlockBreakListener(LandsPlugin landsPlugin) {
        this.plugin = landsPlugin;
        this.landRepository = landsPlugin.getLandRepository();
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Land land = landRepository.getLandAt(block.getLocation());

        if (land.hasFlag(Flag.AUTO_REPLANT)) {
            Material material = block.getType();

            if (material == Material.SUGAR_CANE
                    && block.getRelative(BlockFace.DOWN).getType() == Material.SUGAR_CANE) {
                return;
            }

            if (cropList.contains(material)) {
                BlockData bd = block.getBlockData();
                Ageable age = (Ageable) bd;

                if (age.getAge() == age.getMaximumAge()) {
                    plugin.getScheduler().runLater(() -> block.setType(material), 1L);
                    return;
                }
            }
        }

        if (!land.isBypassing(event.getPlayer(), Action.BLOCK_BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onVehicleBreak(VehicleDestroyEvent event) {
        if (event.getAttacker() instanceof Player) {
            Land land = landRepository.getLandAt(event.getVehicle().getLocation());
            if (!land.isBypassing((Player) event.getAttacker(), Action.VEHICLE_PLACE_BREAK)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getHitBlock() == null) {
            return;
        }

        if (event.getEntity().getShooter() instanceof Player player) {
            Land land = landRepository.getLandAt(event.getHitBlock().getLocation());

            if (!land.isBypassing(player, Action.BLOCK_BREAK)) {
                event.setCancelled(true);
            }
        }
    }
}
