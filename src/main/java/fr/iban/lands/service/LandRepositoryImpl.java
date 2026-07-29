package fr.iban.lands.service;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.enums.LinkType;
import fr.iban.lands.guild.AbstractGuildDataAccess;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.*;
import fr.iban.lands.storage.SqlStorage;
import fr.iban.lands.storage.Storage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class LandRepositoryImpl implements LandRepository {

    private final UUID WILDERNESS_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final LandsPlugin plugin;
    private final Storage storage;
    private final Logger logger;
    private final Map<UUID, Land> lands = new ConcurrentHashMap<>();
    private final Map<SChunk, Land> chunks = new ConcurrentHashMap<>();
    private final Map<String, Land> worldsDefaultLands = new ConcurrentHashMap<>();
    private SystemLand wilderness = new SystemLand(WILDERNESS_ID, "Zone sauvage");

    public LandRepositoryImpl(LandsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storage = new SqlStorage(logger);
        loadLands();
    }

    private void loadLands() {
        plugin.runAsyncQueued(() -> {
            final long start = System.currentTimeMillis();
            logger.info("Loading lands...");
            lands.putAll(storage.getLands());

            storage.getSubLands().forEach((subLandID, superLandID) -> {
                SubLand subLand = (SubLand) lands.get(subLandID);
                Land superLand = lands.get(superLandID);

                if (subLand == null || superLand == null) {
                    plugin.getLogger().severe("Subland or superland not found " + subLandID + " " + superLandID);
                    return;
                }

                subLand.setSuperLand(superLand);
                superLand.getSubLands().put(subLandID, subLand);
            });

            Map<UUID, Map<LinkType, UUID>> links = storage.getLinks();

            links.forEach((landID, map) -> {
                Land land = lands.get(landID);

                map.forEach((linkType, linkedLandID) -> {
                    Land linkedLand = lands.get(linkedLandID);

                    if (land == null || linkedLand == null) {
                        return;
                    }

                    land.setLink(linkType, linkedLand);
                });
            });


            logger.info("Loading chunks...");
            storage.getChunks().forEach((chunk, landId) -> chunks.put(chunk, lands.get(landId)));

            logger.info("Loading worlds default lands...");
            storage.getWorldsDefaultLands().forEach((world, landId) -> {
                Land land = lands.get(landId);

                if (land == null) {
                    plugin.getLogger().severe("Land not found for world default land " + landId);
                    return;
                }

                worldsDefaultLands.put(world, land);
            });

            logger.info(String.format("Loaded %d lands and %d chunks in %dms", lands.size(), chunks.size(), System.currentTimeMillis() - start));

            Land wilderness = storage.getLand(WILDERNESS_ID);
            if (wilderness == null) {
                storage.addLand(this.wilderness);
            } else {
                this.wilderness = (SystemLand) wilderness;
            }
        });
    }

    @Override
    public Optional<Land> getLandById(UUID id) {
        return Optional.ofNullable(lands.get(id));
    }

    @Override
    public void addLand(Land land) {
        lands.put(land.getId(), land);
        plugin.runAsyncQueued(() -> storage.addLand(land));
    }

    @Override
    public void updateLand(Land land) {
        plugin.runAsyncQueued(() -> storage.updateLand(land));
    }

    @Override
    public void deleteLand(Land land) {
        List<SChunk> deletedChunks = List.copyOf(getChunks(land));
        deletedChunks.forEach(chunks::remove);
        deletedChunks.forEach(plugin.getClaimVisualization()::syncChunk);

        if (land.hasSubLand()) {
            for (SubLand subland : land.getSubLands().values()) {
                deleteLand(subland);
            }
        }

        if (land instanceof SubLand subLand) {
            Land superLand = subLand.getSuperLand();
            superLand.getSubLands().remove(subLand.getId());
        }

        worldsDefaultLands.entrySet().removeIf(entry -> entry.getValue().equals(land));

        lands.remove(land.getId());
        plugin.runAsyncQueued(() -> storage.deleteLand(land));
    }

    @Override
    public void reloadLand(UUID id) {
        Land updatedLand = storage.getLand(id);
        Land cachedLand = getLandById(id).orElse(null);

        // Invalidate the cache for the old land
        if (cachedLand != null) {
            if (updatedLand == null) { // Land has been deleted
                getChunks(cachedLand).forEach(chunks::remove);
                lands.remove(cachedLand.getId());
            } else { // Land has been updated
                getChunks(cachedLand).forEach(schunk -> chunks.put(schunk, updatedLand));
            }
        }

        // Update the cache with the new land
        if (updatedLand != null) {
            lands.put(updatedLand.getId(), updatedLand);
        }
    }

    @Override
    public Land getLandAt(Chunk chunk) {
        return getLandAt(new SChunk(chunk));
    }

    @Override
    public Land getLandAt(SChunk schunk) {
        Land defaultLand = worldsDefaultLands.getOrDefault(schunk.getWorld(), wilderness);
        return chunks.getOrDefault(schunk, defaultLand);
    }

    @NotNull
    @Override
    public Land getLandAt(Location location) {
        Land land = getLandAt(new SChunk(location));
        SubLand subLand = land.getSubLandAt(location);
        return Objects.requireNonNullElse(subLand, land);
    }

    @Override
    public Land getWilderness() {
        return wilderness;
    }

    @Override
    public boolean isWilderness(Land land) {
        return land.equals(wilderness);
    }

    @Override
    public void addChunk(SChunk chunk, Land land) {
        removeChunk(chunk);
        chunks.put(chunk, land);
        plugin.runAsyncQueued(() -> storage.setChunk(land, chunk));
    }

    @Override
    public void removeChunk(SChunk chunk) {
        chunks.remove(chunk);
        plugin.runAsyncQueued(() -> storage.removeChunk(chunk));
    }

    @Override
    public Map<SChunk, Land> getChunks() {
        return chunks;
    }

    @Override
    public Collection<SChunk> getChunks(Land land) {
        return chunks.entrySet().stream()
                .filter(entry -> entry.getValue().equals(land))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public List<Land> getLands() {
        return new ArrayList<>(lands.values());
    }

    @Override
    public List<Land> getLands(UUID uuid) {
        Predicate<Land> uuidEquals = land -> land.getOwner() != null && land.getOwner().equals(uuid);
        return lands.values().stream().filter(uuidEquals).toList();
    }

    @Override
    public List<Land> getLands(UUID uuid, LandType type) {
        return getLands(uuid).stream()
                .filter(land -> land.getType() == type)
                .toList();
    }

    @Override
    public List<Land> getGuildLands(UUID uuid) {
        return getLands(uuid, LandType.GUILD).stream().toList();
    }

    @Override
    public List<Land> getSystemLands() {
        return lands.values().stream()
                .filter(SystemLand.class::isInstance)
                .toList();
    }

    @Override
    public List<Land> getSystemLands(String name) {
        return getSystemLands().stream()
                .filter(land -> land.getName().equalsIgnoreCase(name))
                .toList();
    }

    @Override
    public List<String> getManageableLandsNames(Player player) {
        List<String> landNames = new ArrayList<>();
        UUID playerId = player.getUniqueId();

        for (Land land : getLands(playerId, LandType.PLAYER)) {
            landNames.add(land.getName());
        }

        if (plugin.isGuildsHookEnabled() && plugin.getGuildDataAccess().canManageGuildLand(playerId)) {
            UUID guildID = plugin.getGuildDataAccess().getGuildId(playerId);

            if (guildID != null) {
                for (Land land : getGuildLands(plugin.getGuildDataAccess().getGuildId(playerId))) {
                    landNames.add("guild:" + land.getName());
                }
            }
        }

        if (plugin.isBypassing(player)) {
            for (Land land : getSystemLands()) {
                landNames.add("system:" + land.getName());

                for (Land subLand : land.getSubLands().values()) {
                    landNames.add("system:subland:" + subLand.getName());
                }
            }
        }
        return landNames;
    }

    @Override
    public Land getManageableLandFromName(Player player, String name) {
        UUID playerId = player.getUniqueId();

        if (name.startsWith("system:") && player.hasPermission("lands.bypass")) {
            if(name.startsWith("system:subland:")) {
                return getSystemLands().stream()
                        .map(land -> land.getSubLands().values())
                        .flatMap(Collection::stream)
                        .filter(land -> land.getName().equals(name.split(":")[2]))
                        .findFirst().orElse(null);
            }

            return getSystemLands().stream()
                    .filter(land -> land.getName().equals(name.split(":")[1]))
                    .findFirst().orElse(null);
        }
        if (name.startsWith("guild:") && plugin.getGuildDataAccess() != null) {
            if (plugin.getGuildDataAccess().canManageGuildLand(playerId)) {
                return getGuildLands(plugin.getGuildDataAccess().getGuildId(playerId)).stream()
                        .filter(land -> land.getName().equals(name.split(":")[1]))
                        .findFirst().orElse(null);
            }
        }

        return getLands(playerId, LandType.PLAYER).stream()
                .filter(land -> land.getName().equals(name))
                .findFirst().orElse(null);
    }

    @Override
    public boolean canManageLand(Player player, Land land) {
        UUID playerId = player.getUniqueId();

        if (land instanceof GuildLand guildLand) {
            AbstractGuildDataAccess guildDataAccess = plugin.getGuildDataAccess();

            return guildDataAccess != null
                    && guildDataAccess.canManageGuildLand(playerId)
                    && getGuildLands(guildDataAccess.getGuildId(playerId)).contains(guildLand);
        } else if (land instanceof PlayerLand playerLand) {
            return playerLand.getOwner().equals(playerId);
        } else if (land instanceof SystemLand) {
            return player.hasPermission("lands.bypass");
        }

        return false;
    }

    @Override
    public void addTrust(Land land, UUID uuid, Action action) {
        land.trust(uuid, action);
        plugin.runAsyncQueued(() -> storage.addTrust(land, uuid, action));
    }

    @Override
    public void removeTrust(Land land, UUID uuid, Action action) {
        land.untrust(uuid, action);
        plugin.runAsyncQueued(() -> storage.removeTrust(land, uuid, action));
    }

    @Override
    public void addGlobalTrust(Land land, Action action) {
        land.trust(action);
        plugin.runAsyncQueued(() -> storage.addGlobalTrust(land, action));
    }

    @Override
    public void removeGlobalTrust(Land land, Action action) {
        land.untrust(action);
        plugin.runAsyncQueued(() -> storage.removeGlobalTrust(land, action));
    }

    @Override
    public void addGuildTrust(Land land, Action action) {
        land.trustGuild(action);
        plugin.runAsyncQueued(() -> storage.addGuildTrust(land, action));
    }

    @Override
    public void removeGuildTrust(Land land, Action action) {
        land.untrustGuild(action);
        plugin.runAsyncQueued(() -> storage.removeGuildTrust(land, action));
    }

    @Override
    public int getChunkLimit(UUID uuid) {
        return storage.getChunkLimit(uuid);
    }

    @Override
    public int getChunkCount(UUID uuid) {
        return storage.getChunkCount(uuid);
    }

    @Override
    public int getRemainingChunkCount(UUID uuid) {
        if(uuid == null) {
            return 9999999;
        }

        int max = getMaxChunkCount(uuid);
        int count = getChunkCount(uuid);

        return max - count;
    }

    @Override
    public void setChunkLimit(UUID from, int limit) {
        plugin.runAsyncQueued(() -> storage.setChunkLimit(from, limit));
    }

    @Override
    public void increaseChunkLimit(UUID to, int maxChunkCount) {
        plugin.runAsyncQueued(() -> storage.increaseChunkLimit(to, maxChunkCount));
    }

    @Override
    public void decreaseChunkLimit(UUID uuid, int amount) {
        plugin.runAsyncQueued(() -> storage.decreaseChunkLimit(uuid, amount));
    }

    @Override
    public void setDefaultWorldLand(World world, Land land) {
        worldsDefaultLands.put(world.getName(), land);
        storage.setWorldDefaultLand(world.getName(), land);
    }

    @Override
    public int getMaxChunkCount(UUID uuid) {
        if (plugin.isBypassing(uuid)) {
            return 1000000;
        }

        return getChunkLimit(uuid);
    }

    @Override
    public void addFlag(Land land, Flag flag) {
        land.getFlags().add(flag);
        plugin.runAsyncQueued(() -> storage.addFlag(land, flag));
    }

    @Override
    public void removeFlag(Land land, Flag flag) {
        land.getFlags().remove(flag);
        plugin.runAsyncQueued(() -> storage.removeFlag(land, flag));
    }

    @Override
    public void addLink(Land land, LinkType link, Land with) {
        land.setLink(link, with);
        plugin.runAsyncQueued(() -> storage.addLink(land, link, with));
    }

    @Override
    public void removeLink(Land land, LinkType link) {
        land.removeLink(link);
        plugin.runAsyncQueued(() -> storage.removeLink(land, link));
    }

    @Override
    public void addBan(Land land, UUID uuid) {
        land.getBans().add(uuid);
        plugin.runAsyncQueued(() -> storage.addBan(land, uuid));
    }

    @Override
    public void removeBan(Land land, UUID uuid) {
        land.getBans().remove(uuid);
        plugin.runAsyncQueued(() -> storage.removeBan(land, uuid));
    }

    @Override
    public void addEffect(Land land, PotionEffectType effectType, int amplifier) {
        land.addEffect(effectType, amplifier);
        plugin.runAsyncQueued(() -> storage.addEffect(land, effectType.getKey().toString(), amplifier));
    }

    @Override
    public void removeEffect(Land land, PotionEffectType effectType) {
        land.removeEffect(effectType);
        plugin.runAsyncQueued(() -> storage.removeEffect(land, effectType.getKey().toString()));
    }

    @Override
    public void addCommand(Land land, LandEnterCommand command) {
        land.addCommand(command);
        plugin.runAsyncQueued(() -> storage.addCommand(land, command));
    }

    @Override
    public void removeCommand(Land land, LandEnterCommand command) {
        land.removeCommand(command);
        plugin.runAsyncQueued(() -> storage.removeCommand(command));
    }
}
