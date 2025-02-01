package fr.iban.lands.listeners;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.model.land.Land;
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class InteractListener implements Listener {

    private final LandRepository landRepository;
    private final LandsPlugin plugin;
    private final List<Location> toClose = new ArrayList<>();

    public InteractListener(LandsPlugin landsPlugin) {
        this.plugin = landsPlugin;
        this.landRepository = landsPlugin.getLandRepository();
    }

    @EventHandler
    public void onPhysics(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        if (block == null) return;

        Material material = block.getType();
        Land land = landRepository.getLandAt(block.getLocation());

        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL
                && material == Material.FARMLAND
                && !land.hasFlag(Flag.FARMLAND_GRIEF)) {
            event.setCancelled(true);
            return;
        }

        if (material == Material.ARMOR_STAND
                && !land.isBypassing(player, Action.ARMOR_STAND_INTERACT)) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {

            if (land.hasFlag(Flag.DOORS_AUTOCLOSE)) {
                if (block.getBlockData() instanceof Openable openable) {
                    if (!land.isBypassing(event.getPlayer(), Action.ALL) && !toClose.contains(block.getLocation())) {
                        boolean opened = openable.isOpen();
                        toClose.add(block.getLocation());
                        plugin.getScheduler().runAtLocationLater(block.getLocation(), () -> {
                            Openable o = (Openable) block.getBlockData();
                            o.setOpen(opened);
                            block.setBlockData(o);
                            block.getState().update();
                            toClose.remove(block.getLocation());
                        }, 60L);
                    }
                }
            }

            ItemStack itemInMainHand = player.getInventory().getItemInMainHand();
            ItemStack itemInOffHand = player.getInventory().getItemInOffHand();

            boolean hasSpawnEggInHand = itemInMainHand.getType().toString().contains("SPAWN_EGG")
                    || itemInOffHand.getType().toString().contains("SPAWN_EGG");

            if ((material == Material.ANVIL && !land.isBypassing(player, Action.USE_ANVIL))
                    || (material == Material.BREWING_STAND && !land.isBypassing(player, Action.BREWING_STAND_INTERACT))
                    || ((block.getBlockData() instanceof Powerable && material != Material.LECTERN) && !land.isBypassing(player, Action.USE))
                    || (block.getBlockData() instanceof Bed && !land.isBypassing(player, Action.USE_BED))
                    || (block.getBlockData() instanceof RespawnAnchor && !land.isBypassing(player, Action.USE_RESPAWN_ANCHOR))
                    || ((material == Material.FLOWER_POT
                    || material.name().startsWith("POTTED_")) && !land.isBypassing(player, Action.FLOWER_POT_INTERACT))
                    || (material == Material.DRAGON_EGG && !land.isBypassing(player, Action.DRAGON_EGG_INTERACT))
                    || (((block.getState() instanceof InventoryHolder && material != Material.LECTERN && material != Material.BREWING_STAND) || material == Material.JUKEBOX) && !land.isBypassing(player, Action.OPEN_CONTAINER))
                    || (material == Material.LECTERN && !land.isBypassing(player, Action.LECTERN_READ))
                    || (material == Material.CHISELED_BOOKSHELF && !land.isBypassing(player, Action.CHISELED_BOOKSHELF_INTERACT))
                    || hasVehiculeInHand(player) && !land.isBypassing(player, Action.VEHICLE_PLACE_BREAK)
                    || hasArmorStandInHand(player) && !land.isBypassing(player, Action.BLOCK_PLACE)
                    || hasSpawnEggInHand && !land.isBypassing(player, Action.USE_SPAWN_EGG)) {
                event.setCancelled(true);
            }

        } else if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            if (material == Material.DRAGON_EGG && !land.isBypassing(player, Action.DRAGON_EGG_INTERACT)) {
                event.setCancelled(true);
            }
        } else if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            if (material == Material.BIG_DRIPLEAF) {
                return;
            }
            if (!land.isBypassing(player, Action.PHYSICAL_INTERACT)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityInteract(EntityInteractEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();
        Land land = landRepository.getLandAt(block.getLocation());

        if (event.getEntityType() != EntityType.VILLAGER) {
            if (land.hasFlag(Flag.PRESSURE_PLATE_BY_ENTITY)) {
                switch (material) {
                    case ACACIA_PRESSURE_PLATE,
                         OAK_PRESSURE_PLATE,
                         DARK_OAK_PRESSURE_PLATE,
                         JUNGLE_PRESSURE_PLATE,
                         BIRCH_PRESSURE_PLATE,
                         CRIMSON_PRESSURE_PLATE,
                         WARPED_PRESSURE_PLATE,
                         STONE_PRESSURE_PLATE,
                         SPRUCE_PRESSURE_PLATE,
                         LIGHT_WEIGHTED_PRESSURE_PLATE,
                         POLISHED_BLACKSTONE_PRESSURE_PLATE -> {
                        return;
                    }
                }
            }

            if (land.hasFlag(Flag.TRIPWIRE_BY_ENTITY) && material == Material.TRIPWIRE) {
                return;
            }

            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFerilize(BlockFertilizeEvent event) {
        Player player = event.getPlayer();

        if (player == null) return;

        Block block = event.getBlock();

        Land land = landRepository.getLandAt(block.getLocation());

        if (!land.isBypassing(player, Action.FERTILIZE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAtEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Land land = landRepository.getLandAt(event.getRightClicked().getLocation());

        if (!land.isBypassing(player, Action.ENTITY_INTERACT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLeash(PlayerLeashEntityEvent event) {
        Player player = event.getPlayer();
        Land land = landRepository.getLandAt(event.getEntity().getLocation());

        if (!land.isBypassing(player, Action.LEASH)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBeaconChange(PlayerChangeBeaconEffectEvent event) {
        Player player = event.getPlayer();
        Land land = landRepository.getLandAt(event.getBeacon().getLocation());

        if (!land.isBypassing(player, Action.CHANGE_BEACON_EFFECT)) {
            event.setCancelled(true);
        }
    }

    private boolean hasVehiculeInHand(Player player) {
        return player.getInventory().getItemInMainHand().getType().toString().contains("BOAT")
                || player.getInventory().getItemInMainHand().getType().toString().contains("MINECART")
                || player.getInventory().getItemInOffHand().getType().toString().contains("BOAT")
                || player.getInventory().getItemInOffHand().getType().toString().contains("MINECART");
    }

    private boolean hasArmorStandInHand(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.ARMOR_STAND
                || player.getInventory().getItemInOffHand().getType() == Material.ARMOR_STAND;
    }

    @EventHandler
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        Land land = landRepository.getLandAt(event.getRightClicked().getLocation());

        if (!land.isBypassing(player, Action.ARMOR_STAND_INTERACT)) {
            event.setCancelled(true);
        }
    }
}
