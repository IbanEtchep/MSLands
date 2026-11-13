package fr.iban.lands.model.land;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.enums.LinkType;
import fr.iban.lands.model.Trust;
import fr.iban.lands.messages.LandMessages;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Land {

    /** The wilderness: chunks nobody claimed, in a world without a default land. */
    public static final UUID WILDERNESS_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    protected UUID id;
    protected String name;
    protected Trust globalTrust = new Trust();
    private final Trust guildTrust = new Trust();
    protected Map<UUID, Trust> trusts = new HashMap<>();
    protected Set<PotionEffect> effects = new HashSet<>();
    protected Set<LandEnterCommand> enterCommands = new HashSet<>();
    protected Set<Flag> flags = new HashSet<>();
    protected Set<UUID> bans = new HashSet<>();
    protected Map<LinkType, Land> links;
    protected Map<UUID, SubLand> subLands = new ConcurrentHashMap<>();
    protected Date createdAt = new Date();

    public Land(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public abstract @Nullable UUID getOwner();

    public abstract LandType getType();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /*
     * TRUSTS
     */

    public Trust getGlobalTrust() {
        Land linkedLand = getLinkedLand(LinkType.GLOBALTRUST);
        if (linkedLand != null) {
            return linkedLand.getGlobalTrust();
        }
        return globalTrust;
    }

    public void setGlobalTrust(Trust globalTrust) {
        this.globalTrust = globalTrust;
    }

    public Map<UUID, Trust> getTrusts() {
        Land linkedLand = getLinkedLand(LinkType.TRUSTS);
        if (linkedLand != null) {
            return linkedLand.getTrusts();
        }
        return trusts;
    }

    public Trust getTrust(UUID uuid) {
        return getTrusts().getOrDefault(uuid, new Trust());
    }

    public void trust(UUID uuid, Action action) {
        if (!getTrusts().containsKey(uuid)) {
            getTrusts().put(uuid, new Trust());
        }
        getTrust(uuid).addPermission(action);
    }

    public void untrust(UUID uuid, Action action) {
        if (!getTrusts().containsKey(uuid)) {
            getTrusts().put(uuid, new Trust());
        }
        getTrust(uuid).removePermission(action);
        if (getTrust(uuid).getPermissions().isEmpty()) {
            getTrusts().remove(uuid);
        }
    }

    public void trust(Action action) {
        getGlobalTrust().addPermission(action);
    }

    public void untrust(Action action) {
        getGlobalTrust().removePermission(action);
    }

    public void trustGuild(Action action) {
        getGuildTrust().addPermission(action);
    }

    public void untrustGuild(Action action) {
        getGuildTrust().removePermission(action);
    }

    public boolean isTrusted(UUID uuid, Action action) {
        return getTrusts().containsKey(uuid) && getTrust(uuid).hasPermission(action);
    }

    public boolean isBypassing(Player player, Action action) {
        UUID uuid = player.getUniqueId();
        LandsPlugin plugin = LandsPlugin.getInstance();
        boolean global = getGlobalTrust().hasPermission(action);
        boolean self = isTrusted(uuid, action);
        boolean guild = plugin.isGuildsHookEnabled()
                && getOwner() != null
                && getGuildTrust().hasPermission(action)
                && plugin.getGuildDataAccess().areInSameGuild(getOwner(), uuid);
        boolean bypassing = plugin.isBypassing(player);
        boolean bypass = global || self || bypassing || guild;

        LandMessages messages = plugin.getMessages();
        if (!bypass && !(this instanceof SystemLand) && messages != null) {
            messages.actionBar(player, "no_permission_here");
        }
        if (plugin.isInDebugMode(player) && messages != null) {
            messages.send(player, "admin.debug.detail", "action", action.name(), "global", global, "self", self,
                    "guild", guild, "bypass", bypassing);
        }
        return bypass;
    }

    public void setTrusts(Map<UUID, Trust> trusts) {
        this.trusts = trusts;
    }

    /*
     * FLAGS
     */

    public Set<Flag> getFlags() {
        return flags;
    }

    public void setFlags(Set<Flag> flags) {
        this.flags = flags;
    }

    public boolean hasFlag(Flag flag) {
        return getFlags().contains(flag);
    }

    public Set<UUID> getBans() {
        Land linkedLand = getLinkedLand(LinkType.BANS);
        if (linkedLand != null) {
            return linkedLand.getBans();
        }
        return bans;
    }

    public void setBans(Set<UUID> bans) {
        this.bans = bans;
    }

    public boolean isBanned(UUID uuid) {
        return getBans().contains(uuid);
    }

    public Map<LinkType, Land> getLinks() {
        if (links == null) {
            links = new EnumMap<>(LinkType.class);
        }
        return links;
    }

    public void setLink(LinkType link, Land with) {
        getLinks().put(link, with);
    }

    public void removeLink(LinkType link) {
        getLinks().remove(link);
    }

    public Land getLinkedLand(LinkType link) {
        Land land = getLinks().get(link);
        if (land == null) {
            removeLink(link);
        }
        return land;
    }

    public boolean hasSubLand() {
        return getType() != LandType.SUBLAND && getSubLands() != null && !getSubLands().isEmpty();
    }

    public SubLand getSubLandAt(Location loc) {
        return subLands.values().stream()
                .filter(subland -> subland.getCuboid() != null
                        && subland.getCuboid().contains(loc)
                        && LandsPlugin.getInstance().getServerName().equals(subland.getServer()))
                .findFirst()
                .orElse(null);
    }

    public void setSubLands(Map<UUID, SubLand> subLands) {
        this.subLands = subLands;
    }

    public Map<UUID, SubLand> getSubLands() {
        return subLands;
    }

    public Trust getGuildTrust() {
        return guildTrust;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Set<PotionEffect> getEffects() {
        return effects;
    }

    public void addEffect(PotionEffectType effectType, int amplifier) {
        var effect = new PotionEffect(effectType, 160, amplifier); // 160 ticks = 8 seconds
        effects.removeIf(e -> e.getType().equals(effectType));
        effects.add(effect);
    }

    public void removeEffect(PotionEffectType effectType) {
        effects.removeIf(effect -> effect.getType().equals(effectType));
    }

    public void addCommand(LandEnterCommand command) {
        enterCommands.add(command);
    }

    public void removeCommand(LandEnterCommand command) {
        enterCommands.remove(command);
    }

    public Set<LandEnterCommand> getEnterCommands() {
        return enterCommands;
    }

    public void setEnterCommands(Set<LandEnterCommand> enterCommands) {
        this.enterCommands = enterCommands;
    }
}
