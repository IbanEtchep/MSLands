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
import fr.iban.lands.storage.Storage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class LandRepositoryImpl implements LandRepository {

    private static final UUID WILDERNESS_ID = Land.WILDERNESS_ID;

    private final LandsPlugin plugin;
    private final Storage storage;
    private final Logger logger;
    private final Map<UUID, Land> lands = new ConcurrentHashMap<>();
    private final Map<SChunk, Land> chunks = new ConcurrentHashMap<>();
    private final Map<String, Land> worldsDefaultLands = new ConcurrentHashMap<>();
    private SystemLand wilderness = new SystemLand(WILDERNESS_ID, "Zone sauvage");
    /** Told once a change is in the database: {@link LandSync} tells the other servers. */
    private volatile LandChanges changes = LandChanges.NONE;

    /** What this server changed, once saved. Called on the database thread. */
    public interface LandChanges {
        LandChanges NONE = new LandChanges() {
            @Override
            public void landChanged(UUID id) {
            }

            @Override
            public void worldsChanged() {
            }
        };

        /** {@code id} was created, changed or deleted. */
        void landChanged(UUID id);

        /** The lands covering whole worlds changed. */
        void worldsChanged();
    }

    /** What the database holds of one land, read for another server's change. */
    public record Fresh(UUID id, @Nullable Land land, @Nullable UUID superLandId, Map<LinkType, UUID> links) {}

    /** Reads every land and this server's chunks before returning: a failure throws, the plugin then stops. */
    public LandRepositoryImpl(LandsPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storage = storage;
        loadLands();
    }

    private void loadLands() {
        {
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
            storage.getChunks().forEach((chunk, landId) -> {
                Land land = lands.get(landId);
                if (land != null) chunks.put(chunk, land);
            });

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

            if (lands.get(WILDERNESS_ID) instanceof SystemLand loaded) {
                this.wilderness = loaded;
            } else {
                storage.addLand(this.wilderness);
            }
        }
    }

    @Override
    public Optional<Land> getLandById(UUID id) {
        return Optional.ofNullable(lands.get(id));
    }

    @Override
    public void addLand(Land land) {
        lands.put(land.getId(), land);
        save(land, () -> storage.addLand(land));
    }

    @Override
    public void updateLand(Land land) {
        save(land, () -> storage.updateLand(land));
    }

    @Override
    public void deleteLand(Land land) {
        if (land.hasSubLand()) {
            for (SubLand subland : land.getSubLands().values()) {
                deleteLand(subland);
            }
        }
        forget(land);
        save(land, () -> storage.deleteLand(land));
    }

    /** {@code land} out of memory: its chunks, its place in its parent and the worlds it covers. */
    private void forget(Land land) {
        getChunks(land).forEach(chunks::remove);

        if (land instanceof SubLand subLand) {
            Land superLand = subLand.getSuperLand();
            superLand.getSubLands().remove(subLand.getId());
        }

        worldsDefaultLands.entrySet().removeIf(entry -> entry.getValue().equals(land));

        lands.remove(land.getId());
    }

    /** Reads the land again and applies it, on the calling thread: {@link #read} then {@link #apply}. */
    @Override
    public void reloadLand(UUID id) {
        apply(read(id));
    }

    public void setChanges(LandChanges changes) {
        this.changes = changes;
    }

    /** {@code write} on the database thread, then the other servers told about {@code land}. */
    private void save(Land land, Runnable write) {
        UUID id = land.getId();
        plugin.runAsyncQueued(() -> {
            write.run();
            changes.landChanged(id);
        });
    }

    /** One land as the database holds it (database thread). */
    public Fresh read(UUID id) {
        Land land = storage.getLand(id);
        if (land == null) return new Fresh(id, null, null, Map.of());
        return new Fresh(id, land, land instanceof SubLand ? storage.getSuperLandId(id) : null, storage.getLinks(id));
    }

    /**
     * Another server's change to one land, in memory here. A known land keeps its instance (what
     * the chunks, Warps and MSBoosts hold) and takes the new state; a new one is added; a deleted
     * one is forgotten with its sub-lands. Its parent and links are set again from the database.
     */
    public void apply(Fresh fresh) {
        Land known = lands.get(fresh.id());
        if (fresh.land() == null) {
            if (known != null) forgetWithSubLands(known);
            return;
        }
        Land land;
        if (known != null && known.getClass() == fresh.land().getClass()
                && Objects.equals(known.getOwner(), fresh.land().getOwner())) {
            known.refreshFrom(fresh.land());
            land = known;
        } else {
            Land replacement = fresh.land();
            land = replacement;
            if (known != null) {
                // Another owner: a new instance, which takes over the old one's chunks and sub-lands.
                replacement.setSubLands(known.getSubLands());
                getChunks(known).forEach(chunk -> chunks.put(chunk, replacement));
                worldsDefaultLands.replaceAll((world, l) -> l == known ? replacement : l);
                if (known instanceof SubLand sub && sub.getSuperLand() != null) {
                    sub.getSuperLand().getSubLands().remove(known.getId());
                }
            }
            lands.put(land.getId(), land);
            if (WILDERNESS_ID.equals(land.getId()) && land instanceof SystemLand system) wilderness = system;
        }
        if (land instanceof SubLand sub && fresh.superLandId() != null) {
            Land superLand = lands.get(fresh.superLandId());
            if (superLand != null) {
                sub.setSuperLand(superLand);
                superLand.getSubLands().put(sub.getId(), sub);
            }
        }
        Map<LinkType, Land> links = new EnumMap<>(LinkType.class);
        fresh.links().forEach((type, linkedId) -> {
            Land linked = lands.get(linkedId);
            if (linked != null) links.put(type, linked);
        });
        land.getLinks().keySet().retainAll(links.keySet());
        land.getLinks().putAll(links);
    }

    private void forgetWithSubLands(Land land) {
        for (SubLand subLand : List.copyOf(land.getSubLands().values())) forgetWithSubLands(subLand);
        forget(land);
    }

    /** The lands covering whole worlds, as the database holds them (database thread). */
    public Map<String, UUID> readWorldDefaults() {
        return storage.getWorldsDefaultLands();
    }

    public void applyWorldDefaults(Map<String, UUID> defaults) {
        Map<String, Land> resolved = new HashMap<>();
        defaults.forEach((world, landId) -> {
            Land land = lands.get(landId);
            if (land != null) resolved.put(world, land);
        });
        worldsDefaultLands.keySet().retainAll(resolved.keySet());
        worldsDefaultLands.putAll(resolved);
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
        save(land, () -> storage.addTrust(land, uuid, action));
    }

    @Override
    public void removeTrust(Land land, UUID uuid, Action action) {
        land.untrust(uuid, action);
        save(land, () -> storage.removeTrust(land, uuid, action));
    }

    @Override
    public void addGlobalTrust(Land land, Action action) {
        land.trust(action);
        save(land, () -> storage.addGlobalTrust(land, action));
    }

    @Override
    public void removeGlobalTrust(Land land, Action action) {
        land.untrust(action);
        save(land, () -> storage.removeGlobalTrust(land, action));
    }

    @Override
    public void addGuildTrust(Land land, Action action) {
        land.trustGuild(action);
        save(land, () -> storage.addGuildTrust(land, action));
    }

    @Override
    public void removeGuildTrust(Land land, Action action) {
        land.untrustGuild(action);
        save(land, () -> storage.removeGuildTrust(land, action));
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
        plugin.runAsyncQueued(() -> {
            storage.setWorldDefaultLand(world.getName(), land);
            changes.worldsChanged();
        });
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
        save(land, () -> storage.addFlag(land, flag));
    }

    @Override
    public void removeFlag(Land land, Flag flag) {
        land.getFlags().remove(flag);
        save(land, () -> storage.removeFlag(land, flag));
    }

    @Override
    public void addLink(Land land, LinkType link, Land with) {
        land.setLink(link, with);
        save(land, () -> storage.addLink(land, link, with));
    }

    @Override
    public void removeLink(Land land, LinkType link) {
        land.removeLink(link);
        save(land, () -> storage.removeLink(land, link));
    }

    @Override
    public void addBan(Land land, UUID uuid) {
        land.getBans().add(uuid);
        save(land, () -> storage.addBan(land, uuid));
    }

    @Override
    public void removeBan(Land land, UUID uuid) {
        land.getBans().remove(uuid);
        save(land, () -> storage.removeBan(land, uuid));
    }

    @Override
    public void addEffect(Land land, PotionEffectType effectType, int amplifier) {
        land.addEffect(effectType, amplifier);
        save(land, () -> storage.addEffect(land, effectType.getKey().toString(), amplifier));
    }

    @Override
    public void removeEffect(Land land, PotionEffectType effectType) {
        land.removeEffect(effectType);
        save(land, () -> storage.removeEffect(land, effectType.getKey().toString()));
    }

    @Override
    public void addCommand(Land land, LandEnterCommand command) {
        land.addCommand(command);
        save(land, () -> storage.addCommand(land, command));
    }

    @Override
    public void removeCommand(Land land, LandEnterCommand command) {
        land.removeCommand(command);
        save(land, () -> storage.removeCommand(command));
    }
}
