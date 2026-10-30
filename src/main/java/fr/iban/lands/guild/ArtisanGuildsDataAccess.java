package fr.iban.lands.guild;

import com.github.ibanetchep.artisanguilds.api.ArtisanGuildsApi;
import com.github.ibanetchep.artisanguilds.api.Guild;
import com.github.ibanetchep.artisanguilds.api.GuildMember;
import com.github.ibanetchep.artisanguilds.api.GuildPermission;
import com.github.ibanetchep.artisanguilds.api.event.GuildDisbandEvent;
import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

import java.util.UUID;

/**
 * Guild lands through ArtisanGuilds, which replaces MSGuilds. Its API is registered once the
 * guilds are loaded, possibly after this plugin: the listener stays registered and picks it up.
 * Guild ids are kept by ArtisanGuilds' import, so existing guild lands keep their owner. Until the
 * API is there, every lookup answers as if no guild existed.
 */
public class ArtisanGuildsDataAccess implements AbstractGuildDataAccess, Listener {

    private static final String LOG_REASON = "Lands";

    private final LandsPlugin landsPlugin;
    private ArtisanGuildsApi api;

    public ArtisanGuildsDataAccess(LandsPlugin landsPlugin) {
        this.landsPlugin = landsPlugin;
        landsPlugin.getServer().getPluginManager().registerEvents(this, landsPlugin);
    }

    @Override
    public void load() {
        api = ArtisanGuildsApi.get();
        if (api != null) {
            landsPlugin.getLogger().info("Hooked into ArtisanGuilds.");
        }
    }

    @Override
    public void unload() {
        api = null;
    }

    @Override
    public boolean isEnabled() {
        return api != null;
    }

    @Override
    public boolean guildExists(UUID guildId) {
        return guild(guildId) != null;
    }

    @Override
    public String getGuildName(UUID guildId) {
        Guild guild = guild(guildId);
        return guild == null ? "" : guild.getName();
    }

    @Override
    public UUID getGuildId(UUID uuid) {
        Guild guild = guildOf(uuid);
        return guild == null ? null : guild.getId();
    }

    @Override
    public boolean canManageGuildLand(UUID uuid) {
        ArtisanGuildsApi api = this.api;
        GuildMember member = api == null ? null : api.getMember(uuid);
        return member != null && member.isGranted(GuildPermission.MANAGE_LANDS);
    }

    @Override
    public boolean areInSameGuild(UUID uuid1, UUID uuid2) {
        UUID guild1 = getGuildId(uuid1);
        return guild1 != null && guild1.equals(getGuildId(uuid2));
    }

    @Override
    public boolean hasGuild(UUID uuid) {
        return guildOf(uuid) != null;
    }

    @Override
    public boolean isGuildLeader(UUID uuid, UUID guildId) {
        Guild guild = guild(guildId);
        return guild != null && guild.getOwnerId().equals(uuid);
    }

    @Override
    public UUID getGuildLeader(UUID guildId) {
        Guild guild = guild(guildId);
        return guild == null ? null : guild.getOwnerId();
    }

    @Override
    public boolean isGuildMember(UUID uuid, UUID guildId) {
        Guild guild = guild(guildId);
        return guild != null && guild.isMember(uuid);
    }

    @Override
    public boolean withdraw(UUID guildId, double amount, String reason) {
        ArtisanGuildsApi api = this.api;
        Guild guild = api == null ? null : api.getGuild(guildId);
        return guild != null && api.withdraw(guild, amount, reason);
    }

    @Override
    public boolean withdraw(UUID guildId, double amount) {
        return withdraw(guildId, amount, LOG_REASON);
    }

    @Override
    public boolean deposit(UUID guildId, double amount) {
        ArtisanGuildsApi api = this.api;
        Guild guild = api == null ? null : api.getGuild(guildId);
        return guild != null && api.deposit(guild, amount);
    }

    /** Null when the guild does not exist or ArtisanGuilds is not there. */
    private Guild guild(UUID guildId) {
        ArtisanGuildsApi api = this.api;
        return api == null ? null : api.getGuild(guildId);
    }

    private Guild guildOf(UUID playerId) {
        ArtisanGuildsApi api = this.api;
        return api == null ? null : api.getGuildByPlayer(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(GuildDisbandEvent e) {
        LandService landService = landsPlugin.getLandService();
        LandRepository landRepository = landsPlugin.getLandRepository();
        UUID guildId = e.getGuild().getId();

        landService.transferClaims(guildId, e.getGuild().getOwnerId());

        landRepository.getGuildLands(guildId).forEach(landRepository::deleteLand);
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == ArtisanGuildsApi.class) {
            load();
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == ArtisanGuildsApi.class) {
            api = null;
            landsPlugin.getLogger().info("ArtisanGuilds hook disabled.");
        }
    }
}
