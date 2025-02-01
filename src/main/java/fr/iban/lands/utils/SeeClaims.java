package fr.iban.lands.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.GuildLand;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.PlayerLand;
import fr.iban.lands.model.land.SystemLand;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class SeeClaims {

    private final LandsPlugin plugin;
    private final LandRepository landRepository;
    private final Player player;
    private final Set<Location> visibleBlocks = new HashSet<>();
    private final Map<Location, StateType> wallBlocks = new HashMap<>();
    private WrappedTask proximityTask;
    private static final double HIDE_DISTANCE_SQUARED = 16.0; // 4 blocks
    private static final int VIEW_DISTANCE = 5;
    private static final int VERTICAL_VIEW_RANGE = 90;
    private Location lastPlayerLocation;
    private long lastUpdate = System.currentTimeMillis();

    public SeeClaims(Player player, LandsPlugin plugin) {
        this.player = player;
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
        this.lastPlayerLocation = player.getLocation();
    }

    public void showWalls() {
        updateWalls();
        startProximityCheck();
    }

    /**
     * Démarre la tâche de vérification de proximité
     */
    public void startProximityCheck() {
        proximityTask = plugin.getScheduler().runAtEntityTimer(player, () -> {
            if (!player.isOnline()) {
                stop();
                return;
            }

            updateVisibleBlocks();

            boolean isSameWorld = player.getWorld().equals(lastPlayerLocation.getWorld());

            if ((!isSameWorld || lastPlayerLocation.distanceSquared(player.getLocation()) > 100)
                    && System.currentTimeMillis() - lastUpdate > 1000) {
                updateWalls();

                lastPlayerLocation = player.getLocation();
                lastUpdate = System.currentTimeMillis();
            }
        }, 5L, 5L);
    }

    /**
     * Met à jour les blocs visibles en fonction de la position du joueur
     */
    private void updateVisibleBlocks() {
        Location playerLoc = player.getLocation();
        Set<Location> newVisibleBlocks = new HashSet<>();

        int py = playerLoc.getBlockY();

        for (Map.Entry<Location, StateType> entry : wallBlocks.entrySet()) {
            Location baseLoc = entry.getKey();

            for (int y = Math.max(0, py - VERTICAL_VIEW_RANGE); y < Math.min(255, py + VERTICAL_VIEW_RANGE); y++) {
                Location blockLoc = baseLoc.clone();
                blockLoc.setY(y);

                if (playerLoc.distanceSquared(blockLoc) < HIDE_DISTANCE_SQUARED
                        || blockLoc.getBlock().getType() != Material.AIR) {
                    // Si le bloc était visible avant, le cacher
                    if (visibleBlocks.contains(blockLoc)) {
                        sendBlockUpdate(blockLoc);
                    }
                    continue;
                }

                newVisibleBlocks.add(blockLoc);
                if (!visibleBlocks.contains(blockLoc)) {
                    // Montrer le nouveau bloc
                    sendBlockUpdate(blockLoc, false, entry.getValue());
                }
            }
        }

        // Cacher les blocs qui ne sont plus visibles
        visibleBlocks.stream()
                .filter(loc -> !newVisibleBlocks.contains(loc))
                .forEach(this::sendBlockUpdate);

        visibleBlocks.clear();
        visibleBlocks.addAll(newVisibleBlocks);
    }

    /**
     * Envoie une mise à jour de bloc au joueur
     */
    private void sendBlockUpdate(Location loc) {
        sendBlockUpdate(loc, true, null);
    }

    /**
     * Envoie une mise à jour de bloc au joueur avec un type spécifique
     */
    private void sendBlockUpdate(Location loc, boolean restore, StateType stateType) {
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
                new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                restore ? SpigotConversionUtil.fromBukkitBlockData(loc.getBlock().getBlockData()).getGlobalId()
                        : WrappedBlockState.getDefaultState(stateType).getGlobalId()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    /**
     * Efface tous les murs actuellement visibles
     */
    private void clearCurrentWalls() {
        for (Location loc : visibleBlocks) {
            sendBlockUpdate(loc);
        }
        visibleBlocks.clear();
    }

    /**
     * Calcule les positions des murs et leurs types
     */
    private Map<Location, StateType> calculateWallBlocks() {
        Map<Location, StateType> locations = new HashMap<>();
        SChunk playerChunk = new SChunk(player.getLocation());

        for (int x = -VIEW_DISTANCE; x <= VIEW_DISTANCE; x++) {
            for (int z = -VIEW_DISTANCE; z <= VIEW_DISTANCE; z++) {
                SChunk chunk = playerChunk.getRelativeChunk(x, z);

                Land land = landRepository.getLandAt(chunk);
                if (land == null || landRepository.isWilderness(land)) {
                    continue;
                }

                int baseY = 0;

                if (shouldShowWall(land, chunk.getNorthChunk())) {
                    for (Location loc : chunk.getNorthWall(baseY)) {
                        locations.put(loc, getLandStateType(player, land));
                    }
                }
                if (shouldShowWall(land, chunk.getSouthChunk())) {
                    for (Location loc : chunk.getSouthWall(baseY)) {
                        locations.put(loc, getLandStateType(player, land));
                    }
                }
                if (shouldShowWall(land, chunk.getEastChunk())) {
                    for (Location loc : chunk.getEastWall(baseY)) {
                        locations.put(loc, getLandStateType(player, land));
                    }
                }
                if (shouldShowWall(land, chunk.getWestChunk())) {
                    for (Location loc : chunk.getWestWall(baseY)) {
                        locations.put(loc, getLandStateType(player, land));
                    }
                }
            }
        }
        return locations;
    }

    /**
     * Détermine le type de bloc à utiliser pour un terrain
     */
    public StateType getLandStateType(Player player, Land land) {
        if(land instanceof PlayerLand pland && pland.getOwner().equals(player.getUniqueId())) {
            return StateTypes.LIME_STAINED_GLASS;
        }

        if (land instanceof SystemLand) {
            return StateTypes.RED_STAINED_GLASS;
        }

        if (land instanceof GuildLand gland && gland.isGuildMember(player.getUniqueId())) {
            return StateTypes.LIGHT_BLUE_STAINED_GLASS;
        }

        return StateTypes.WHITE_STAINED_GLASS;
    }

    /**
     * Détermine si un mur doit être affiché entre deux chunks
     */
    private boolean shouldShowWall(Land currentLand, SChunk adjacent) {
        Land adjacentLand = landRepository.getLandAt(adjacent);
        return currentLand != adjacentLand;
    }

    /**
     * Arrête l'affichage des murs et nettoie les ressources
     */
    public void stop() {
        if (proximityTask != null) {
            proximityTask.cancel();
            proximityTask = null;
        }
        clearCurrentWalls();
        wallBlocks.clear();
    }

    private void updateWalls() {
        // Calculer les nouveaux murs sans effacer les anciens
        Map<Location, StateType> newWallBlocks = calculateWallBlocks();

        // Comparer et mettre à jour uniquement les différences
        updateWallDifferences(newWallBlocks);

        // Mettre à jour la map des murs
        wallBlocks.clear();
        wallBlocks.putAll(newWallBlocks);

        // Mettre à jour les blocs visibles
        updateVisibleBlocks();
    }

    private void updateWallDifferences(Map<Location, StateType> newWallBlocks) {
        // Trouver les blocs à supprimer (présents dans wallBlocks mais pas dans newWallBlocks)
        Set<Location> blocksToRemove = new HashSet<>(wallBlocks.keySet());
        blocksToRemove.removeAll(newWallBlocks.keySet());

        // Trouver les blocs à ajouter ou modifier
        Set<Map.Entry<Location, StateType>> blocksToUpdate = newWallBlocks.entrySet().stream()
                .filter(entry -> !wallBlocks.containsKey(entry.getKey()) ||
                        !wallBlocks.get(entry.getKey()).equals(entry.getValue()))
                .collect(Collectors.toSet());

        // Supprimer les blocs qui ne sont plus nécessaires
        for (Location loc : blocksToRemove) {
            if (visibleBlocks.contains(loc)) {
                sendBlockUpdate(loc);
                visibleBlocks.remove(loc);
            }
        }

        // Mettre à jour les nouveaux blocs ou ceux qui ont changé
        for (Map.Entry<Location, StateType> entry : blocksToUpdate) {
            Location baseLoc = entry.getKey();
            Location playerLoc = player.getLocation();
            int py = playerLoc.getBlockY();

            for (int y = Math.max(0, py - VERTICAL_VIEW_RANGE); y < Math.min(255, py + VERTICAL_VIEW_RANGE); y++) {
                Location blockLoc = baseLoc.clone();
                blockLoc.setY(y);

                if (playerLoc.distanceSquared(blockLoc) < HIDE_DISTANCE_SQUARED
                        || blockLoc.getBlock().getType() != Material.AIR) {
                    continue;
                }

                visibleBlocks.add(blockLoc);
                sendBlockUpdate(blockLoc, false, entry.getValue());
            }
        }
    }

    public void forceUpdate() {
        updateWalls();
    }
}