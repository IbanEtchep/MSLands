package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.guild.AbstractGuildDataAccess;
import fr.iban.lands.menu.LandMenus;
import fr.iban.lands.messages.LandMessages;
import net.artisanmc.modules.api.playercommand.PlayerCommandDeclaration;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/** {@code /lands}: the Artisan menu of a player's, a guild's or the system's lands. */
public class LandsCommand {

    public static final String ROOT = "lands:lands";

    private final LandsPlugin plugin;
    private final LandMessages messages;
    private final CommandSupport support;
    private final boolean playerLandsEnabled;

    public LandsCommand(LandsPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
        this.support = new CommandSupport(plugin);
        this.playerLandsEnabled = plugin.getConfig().getBoolean("players-lands-enabled");
    }

    public PlayerCommandDeclaration declaration() {
        return PlayerCommandDeclaration.builder(ROOT)
                .name("lands")
                .description(Map.of("en", "Your lands", "fr", "Vos territoires"))
                .executor(CommandSupport.player((p, ctx) -> lands(p, null)))
                .child(CommandSupport.sub(ROOT, "menu", "The lands of a player (another one: lands.admin)",
                                "Les territoires d'un joueur (un autre : lands.admin)")
                        .argument(CommandSupport.knownPlayer("player").optional())
                        .executor(CommandSupport.player((p, ctx) -> {
                            if (CommandSupport.string(ctx, "player") == null) {
                                lands(p, null);
                                return;
                            }
                            OfflinePlayer target = support.knownPlayer(p, ctx, "player");
                            if (target != null) lands(p, target);
                        }))
                        .build())
                .child(CommandSupport.sub(ROOT, "system", "The system lands", "Les territoires système")
                        .permission("lands.admin")
                        .executor(CommandSupport.player((p, ctx) -> landsSystem(p)))
                        .build())
                .child(CommandSupport.sub(ROOT, "guild", "The lands of your guild", "Les territoires de votre guilde")
                        .executor(CommandSupport.player((p, ctx) -> landsGuild(p)))
                        .build())
                .build();
    }

    public void lands(Player player, @Nullable OfflinePlayer target) {
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

    public void landsSystem(Player player) {
        plugin.getMenus().openLands(player, LandMenus.Scope.SYSTEM, null);
    }

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
