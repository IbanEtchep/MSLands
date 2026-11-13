package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.guild.AbstractGuildDataAccess;
import fr.iban.lands.menu.LandMenus;
import fr.iban.lands.messages.LandMessages;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.UUID;

/** {@code /lands}: the Artisan menu of a player's, a guild's or the system's lands. */
@Command("lands")
public class LandsCommand {

    private final LandsPlugin plugin;
    private final LandMessages messages;
    private final boolean playerLandsEnabled;

    public LandsCommand(LandsPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
        this.playerLandsEnabled = plugin.getConfig().getBoolean("players-lands-enabled");
    }

    @CommandPlaceholder
    public void lands(Player player) {
        lands(player, null);
    }

    @Subcommand("menu")
    public void lands(Player player, @Optional OfflinePlayer target) {
        if (!playerLandsEnabled && !player.hasPermission("lands.admin")) {
            messages.send(player, "disabled");
            return;
        }

        if (target != null && !player.hasPermission("lands.admin")) {
            return;
        }

        UUID targetId = target == null ? player.getUniqueId() : target.getUniqueId();
        plugin.getMenus().openLands(player, LandMenus.Scope.PLAYER, targetId);
    }

    @Subcommand("system")
    @CommandPermission("lands.admin")
    public void landsSystem(Player player) {
        plugin.getMenus().openLands(player, LandMenus.Scope.SYSTEM, null);
    }

    @Subcommand("guild")
    public void landsGuild(Player player) {
        if (!playerLandsEnabled || !plugin.isGuildsHookEnabled()) {
            messages.send(player, "guild.disabled");
            return;
        }

        AbstractGuildDataAccess guildDataAccess = plugin.getGuildDataAccess();
        UUID guildId = guildDataAccess.getGuildId(player.getUniqueId());

        if (guildId != null && guildDataAccess.canManageGuildLand(player.getUniqueId())) {
            plugin.getMenus().openLands(player, LandMenus.Scope.GUILD, guildId);
        } else {
            messages.send(player, "guild.not_admin");
        }
    }
}
