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

/** The guilds of ArtisanGuilds (formerly MSGuilds), through its public API. */
public class GuildsDataAccess implements AbstractGuildDataAccess, Listener {

    private static final String SERVICE = "com.github.ibanetchep.artisanguilds.api.ArtisanGuildsApi";

    private final LandsPlugin landsPlugin;
    private ArtisanGuildsApi guilds;
    private boolean listening;

    public GuildsDataAccess(LandsPlugin landsPlugin) {
        this.landsPlugin = landsPlugin;
    }

    @Override
    public void load() {
        guilds = ArtisanGuildsApi.get();
        if (guilds == null) return;
        if (!listening) {
            landsPlugin.getServer().getPluginManager().registerEvents(this, landsPlugin);
            listening = true;
        }
        landsPlugin.getLogger().info("Intégration avec ArtisanGuilds effectuée.");
    }

    @Override
    public void unload() {
    }

    public boolean isEnabled() {
        return guilds != null;
    }

    @Override
    public boolean guildExists(UUID guildId) {
        return guilds.getGuild(guildId) != null;
    }

    @Override
    public String getGuildName(UUID guildId) {
        Guild guild = guilds.getGuild(guildId);
        return guild == null ? "" : guild.getName();
    }

    @Override
    public UUID getGuildId(UUID uuid) {
        GuildMember guildPlayer = guilds.getMember(uuid);
        return guildPlayer == null ? null : guildPlayer.getGuild().getId();
    }

    @Override
    public boolean canManageGuildLand(UUID uuid) {
        GuildMember guildPlayer = guilds.getMember(uuid);
        return guildPlayer != null && guildPlayer.isGranted(GuildPermission.MANAGE_LANDS);
    }

    @Override
    public boolean areInSameGuild(UUID uuid1, UUID uuid2) {
        GuildMember guildPlayer = guilds.getMember(uuid1);
        GuildMember guildPlayer2 = guilds.getMember(uuid2);
        return guildPlayer != null
                && guildPlayer2 != null
                && guildPlayer.getGuild().equals(guildPlayer2.getGuild());
    }

    @Override
    public boolean hasGuild(UUID uuid) {
        return guilds.getMember(uuid) != null;
    }

    @Override
    public boolean isGuildLeader(UUID uuid, UUID guildId) {
        GuildMember guildPlayer = guilds.getMember(uuid);
        return guildPlayer != null
                && guildPlayer.getGuild().getId().equals(guildId)
                && guildPlayer.isOwner();
    }

    @Override
    public UUID getGuildLeader(UUID guildId) {
        Guild guild = guilds.getGuild(guildId);
        return guild != null ? guild.getOwnerId() : null;
    }

    @Override
    public boolean isGuildMember(UUID uuid, UUID guildId) {
        GuildMember guildPlayer = guilds.getMember(uuid);
        return guildPlayer != null && guildPlayer.getGuild().getId().equals(guildId);
    }

    @Override
    public boolean withdraw(UUID guildId, double amount, String reason) {
        Guild guild = guilds.getGuild(guildId);
        if (guild == null) {
            return false;
        }
        return guilds.withdraw(guild, amount, reason);
    }

    @Override
    public boolean withdraw(UUID guildId, double amount) {
        Guild guild = guilds.getGuild(guildId);
        if (guild == null) {
            return false;
        }
        return guilds.withdraw(guild, amount, "Territoires");
    }

    @Override
    public boolean deposit(UUID guildId, double amount) {
        Guild guild = guilds.getGuild(guildId);
        if (guild == null) {
            return false;
        }
        return guilds.deposit(guild, amount);
    }

    /** Before the guild goes, on the server where it is disbanded (never fired for another server's). */
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
        if (event.getProvider().getService().getName().equals(SERVICE)) {
            load();
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService().getName().equals(SERVICE)) {
            this.guilds = null;
            landsPlugin.getLogger().info("Intégration avec ArtisanGuilds désactivée.");
        }
    }
}
