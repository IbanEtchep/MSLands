package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.model.land.Land;
import net.artisanmc.modules.api.playercommand.CommandArgument;
import net.artisanmc.modules.api.playercommand.PlayerCommandContext;
import net.artisanmc.modules.api.playercommand.PlayerCommandDeclaration;
import net.artisanmc.modules.api.playercommand.PlayerCommandExecutor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * What the land commands share: the builders, the {@code land} argument (a land the sender manages,
 * by name, as Lamp's {@code LandParameterType} parsed it) and the offline player argument. The
 * suggestions read memory only: Artisan asks for them at each key press.
 */
final class CommandSupport {

    private final LandsPlugin plugin;
    private final LandMessages messages;

    CommandSupport(LandsPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
    }

    /** A sub-command {@code root.path}, named after the last segment of {@code path}. */
    static PlayerCommandDeclaration.Builder sub(String root, String path, String en, String fr) {
        return PlayerCommandDeclaration.builder(root + "." + path)
                .name(path.substring(path.lastIndexOf('.') + 1))
                .description(Map.of("en", en, "fr", fr));
    }

    static PlayerCommandExecutor player(BiConsumer<Player, PlayerCommandContext> run) {
        return ctx -> run.accept(ctx.getPlayer(), ctx);
    }

    static @Nullable String string(PlayerCommandContext ctx, String name) {
        Object value = ctx.getArguments().get(name);
        return value == null ? null : value.toString();
    }

    static int integer(PlayerCommandContext ctx, String name, int fallback) {
        Object value = ctx.getArguments().get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** A land the sender manages, by name. */
    CommandArgument land(String name) {
        return CommandArgument.string(name).suggestions(ctx -> ctx.getSender() instanceof Player player
                ? plugin.getLandRepository().getManageableLandsNames(player)
                : List.of());
    }

    /** A player who has played here, by name: online players are suggested. */
    static CommandArgument knownPlayer(String name) {
        return CommandArgument.string(name).suggestions(ctx -> Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .toList());
    }

    /** The land the argument names, or null after telling the player it does not exist. Absent: null, silently. */
    @Nullable Land land(Player player, PlayerCommandContext ctx, String name) {
        String value = string(ctx, name);
        if (value == null) return null;
        Land land = plugin.getLandRepository().getManageableLandFromName(player, value);
        if (land == null) messages.send(player, "land.not_found", "name", value);
        return land;
    }

    /**
     * The player the argument names, from the server's cache: no lookup at Mojang on the server
     * thread (Lamp's {@code OfflinePlayer} did one for an unknown name). Null after telling the sender.
     */
    @Nullable OfflinePlayer knownPlayer(CommandSender sender, PlayerCommandContext ctx, String name) {
        String value = string(ctx, name);
        if (value == null) return null;
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(value);
        if (target == null) messages.send(sender, "player_unknown", "player", value);
        return target;
    }
}
