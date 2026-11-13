package fr.iban.lands.storage;

import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.enums.LinkType;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.LandEnterCommand;
import fr.iban.lands.model.land.SubLand;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Where the lands are kept. The reads run once at start (and {@link #getLand} on a reload of one
 * land); every write runs on ArtisanLands' single database thread, in the order it was asked.
 */
public interface Storage {

    /** Every land with its trusts, flags, bans, effects, commands and, for a sub-land, its area. */
    Map<UUID, Land> getLands();

    /** One land as {@link #getLands} reads it, null when it is gone. */
    @Nullable Land getLand(UUID landId);

    /** Sub-land id → id of the land it sits in. */
    Map<UUID, UUID> getSubLands();

    /** Land id → its links. */
    Map<UUID, Map<LinkType, UUID>> getLinks();

    /** The land {@code subLandId} belongs to, null when it is not a sub-land (or gone). */
    @Nullable UUID getSuperLandId(UUID subLandId);

    /** The links of one land. */
    Map<LinkType, UUID> getLinks(UUID landId);

    /** The chunks claimed on this server, with their land's id. */
    Map<SChunk, UUID> getChunks();

    /** World of this server → the land a chunk nobody claimed belongs to. */
    Map<String, UUID> getWorldsDefaultLands();

    void setWorldDefaultLand(String world, Land land);

    /** Chunks claimed by the lands of {@code owner} (a player or a guild), on every server. */
    int getChunkCount(UUID owner);

    void addLand(Land land);

    void deleteLand(Land land);

    /** Its name, and a sub-land's area. */
    void updateLand(Land land);

    void updateSublandRegion(SubLand subLand);

    void setChunk(Land land, SChunk chunk);

    void removeChunk(SChunk chunk);

    void addTrust(Land land, UUID uuid, Action action);

    void removeTrust(Land land, UUID uuid, Action action);

    void addGlobalTrust(Land land, Action action);

    void removeGlobalTrust(Land land, Action action);

    void addGuildTrust(Land land, Action action);

    void removeGuildTrust(Land land, Action action);

    void addFlag(Land land, Flag flag);

    void removeFlag(Land land, Flag flag);

    void addBan(Land land, UUID uuid);

    void removeBan(Land land, UUID uuid);

    void addLink(Land land, LinkType link, Land with);

    void removeLink(Land land, LinkType link);

    int getChunkLimit(UUID uuid);

    void setChunkLimit(UUID uuid, int limit);

    void increaseChunkLimit(UUID uuid, int amount);

    void decreaseChunkLimit(UUID uuid, int amount);

    void addEffect(Land land, String effect, int amplifier);

    void removeEffect(Land land, String effect);

    void addCommand(Land land, LandEnterCommand command);

    void removeCommand(LandEnterCommand command);
}
