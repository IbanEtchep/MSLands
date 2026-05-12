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
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class SeeClaims {

    private final LandsPlugin plugin;
    private final LandRepository landRepository;
    private final Player player;
    private Map<Long, Location> visibleBlocks = new HashMap<>();
    private final Map<Location, StateType> wallBlocks = new HashMap<>();
    private WrappedTask proximityTask;
    private static final double HIDE_DISTANCE_SQUARED = 16.0; // 4 blocs
    private static final int VIEW_DISTANCE = 5;
    private static final int VERTICAL_VIEW_RANGE = 90;
    private Location lastPlayerLocation;
    private long lastUpdate = System.currentTimeMillis();

    // Cache partagé entre joueurs : un StateType a toujours le même global id côté packet
    private static final Map<StateType, Integer> SHOW_STATE_ID_CACHE = new HashMap<>();

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

            boolean isSameWorld = player.getWorld().equals(lastPlayerLocation.getWorld());

            if (!isSameWorld
                    || (lastPlayerLocation.distanceSquared(player.getLocation()) > 100
                            && System.currentTimeMillis() - lastUpdate > 1000)) {
                if (!isSameWorld) {
                    // Les Locations en cache référencent l'ancien monde — purge avant rebuild.
                    visibleBlocks.clear();
                }
                updateWalls();
                lastPlayerLocation = player.getLocation();
                lastUpdate = System.currentTimeMillis();
            } else {
                updateVisibleBlocks();
            }
        }, 5L, 5L);
    }

    /**
     * Met à jour les blocs visibles en fonction de la position du joueur.
     * Chemin chaud : appelé toutes les 5 ticks par joueur.
     */
    private void updateVisibleBlocks() {
        Location playerLoc = player.getLocation();
        World playerWorld = playerLoc.getWorld();
        double pxD = playerLoc.getX();
        double pyD = playerLoc.getY();
        double pzD = playerLoc.getZ();
        int py = playerLoc.getBlockY();
        int minY = Math.max(0, py - VERTICAL_VIEW_RANGE);
        int maxY = Math.min(255, py + VERTICAL_VIEW_RANGE);

        Map<Long, Location> oldVisible = visibleBlocks;
        visibleBlocks = new HashMap<>(Math.max(16, (int) (oldVisible.size() / 0.75f) + 1));

        for (Map.Entry<Location, StateType> entry : wallBlocks.entrySet()) {
            Location baseLoc = entry.getKey();
            if (!playerWorld.equals(baseLoc.getWorld())) continue;

            int wx = baseLoc.getBlockX();
            int wz = baseLoc.getBlockZ();

            // Distance horizontale (colonne -> joueur) constante sur toute la colonne
            double ddx = pxD - wx;
            double ddz = pzD - wz;
            double horizSq = ddx * ddx + ddz * ddz;
            // Si la colonne est déjà hors rayon de masquage, aucun Y ne peut déclencher le check distance
            boolean mayHideByDistance = horizSq < HIDE_DISTANCE_SQUARED;

            int stateId = getShowStateId(entry.getValue());

            // Une seule résolution de chunk par colonne (au lieu d'une par Y)
            Chunk chunk = playerWorld.getChunkAt(wx >> 4, wz >> 4);
            int lx = wx & 15;
            int lz = wz & 15;

            for (int y = minY; y < maxY; y++) {
                long key = blockKey(wx, y, wz);

                if (mayHideByDistance) {
                    double ddy = pyD - y;
                    double dsq = horizSq + ddy * ddy;
                    if (dsq < HIDE_DISTANCE_SQUARED) {
                        Location old = oldVisible.remove(key);
                        if (old != null) sendRestorePacket(old);
                        continue;
                    }
                }

                if (chunk.getBlock(lx, y, lz).getType() != Material.AIR) {
                    Location old = oldVisible.remove(key);
                    if (old != null) sendRestorePacket(old);
                    continue;
                }

                Location blockLoc = oldVisible.remove(key);
                if (blockLoc == null) {
                    blockLoc = new Location(playerWorld, wx, y, wz);
                    sendShowPacket(blockLoc, stateId);
                }
                visibleBlocks.put(key, blockLoc);
            }
        }

        // Reste dans oldVisible : positions plus traitées (colonne retirée, autre monde, etc.)
        if (!oldVisible.isEmpty()) {
            for (Location loc : oldVisible.values()) {
                sendRestorePacket(loc);
            }
        }
    }

    private void sendRestorePacket(Location loc) {
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
                new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                SpigotConversionUtil.fromBukkitBlockData(loc.getBlock().getBlockData()).getGlobalId()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private void sendShowPacket(Location loc, int stateId) {
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
                new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                stateId
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private static int getShowStateId(StateType stateType) {
        Integer cached = SHOW_STATE_ID_CACHE.get(stateType);
        if (cached != null) return cached;
        int id = WrappedBlockState.getDefaultState(stateType).getGlobalId();
        SHOW_STATE_ID_CACHE.put(stateType, id);
        return id;
    }

    /**
     * Encode (x, y, z) en long. 26 bits pour x et z (plage ±33M, couvre la world border),
     * 12 bits pour y (0-4095). Pas de collision dans la plage de coordonnées Minecraft valides.
     */
    private static long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | (y & 0xFFF);
    }

    /**
     * Efface tous les murs actuellement visibles
     */
    private void clearCurrentWalls() {
        for (Location loc : visibleBlocks.values()) {
            sendRestorePacket(loc);
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
                StateType stateType = getLandStateType(player, land);

                if (shouldShowWall(land, chunk.getNorthChunk())) {
                    for (Location loc : chunk.getNorthWall(baseY)) {
                        locations.put(loc, stateType);
                    }
                }
                if (shouldShowWall(land, chunk.getSouthChunk())) {
                    for (Location loc : chunk.getSouthWall(baseY)) {
                        locations.put(loc, stateType);
                    }
                }
                if (shouldShowWall(land, chunk.getEastChunk())) {
                    for (Location loc : chunk.getEastWall(baseY)) {
                        locations.put(loc, stateType);
                    }
                }
                if (shouldShowWall(land, chunk.getWestChunk())) {
                    for (Location loc : chunk.getWestWall(baseY)) {
                        locations.put(loc, stateType);
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
        if (land instanceof PlayerLand pland && pland.getOwner().equals(player.getUniqueId())) {
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
        Map<Location, StateType> newWallBlocks = calculateWallBlocks();

        // Si le StateType d'une colonne a changé (revente de claim, changement de guilde),
        // on évince ses blocs de visibleBlocks pour que updateVisibleBlocks les re-émette
        // avec le bon global id. La nouvelle trame SHOW écrase l'ancienne côté client,
        // donc pas de flicker.
        if (!visibleBlocks.isEmpty() && !wallBlocks.isEmpty()) {
            int py = player.getLocation().getBlockY();
            int minY = Math.max(0, py - VERTICAL_VIEW_RANGE);
            int maxY = Math.min(255, py + VERTICAL_VIEW_RANGE);
            for (Map.Entry<Location, StateType> oldEntry : wallBlocks.entrySet()) {
                StateType newType = newWallBlocks.get(oldEntry.getKey());
                if (newType != null && !newType.equals(oldEntry.getValue())) {
                    Location baseLoc = oldEntry.getKey();
                    int wx = baseLoc.getBlockX();
                    int wz = baseLoc.getBlockZ();
                    for (int y = minY; y < maxY; y++) {
                        visibleBlocks.remove(blockKey(wx, y, wz));
                    }
                }
            }
        }

        wallBlocks.clear();
        wallBlocks.putAll(newWallBlocks);
        updateVisibleBlocks();
    }

    public void forceUpdate() {
        updateWalls();
    }
}
