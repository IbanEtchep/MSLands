package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.messages.LandMessages;
import net.artisanmc.modules.api.playercommand.CommandArgument;
import net.artisanmc.modules.api.playercommand.PlayerCommandContext;
import net.artisanmc.modules.api.playercommand.PlayerCommandDeclaration;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** {@code /addmaxclaim}, {@code /removemaxclaim} and {@code /getmaxclaim}: a player's claim limit, from the console too. */
public class MaxClaimsCommand {

    public static final String ADD = "lands:addmaxclaim";
    public static final String REMOVE = "lands:removemaxclaim";
    public static final String GET = "lands:getmaxclaim";

    private final LandRepository landRepository;
    private final LandMessages messages;
    private final CommandSupport support;

    public MaxClaimsCommand(LandsPlugin plugin) {
        this.landRepository = plugin.getLandRepository();
        this.messages = plugin.getMessages();
        this.support = new CommandSupport(plugin);
    }

    public List<PlayerCommandDeclaration> declarations() {
        return List.of(
                PlayerCommandDeclaration.builder(ADD)
                        .name("addmaxclaim")
                        .description(Map.of("en", "Raise a player's claim limit", "fr", "Augmenter la limite de claims d'un joueur"))
                        .permission("lands.admin")
                        .consoleAllowed(true)
                        .argument(CommandSupport.knownPlayer("player"))
                        .argument(CommandArgument.integer("amount"))
                        .executor(ctx -> withTarget(ctx, true, (sender, target, amount) -> {
                            landRepository.increaseChunkLimit(target.getUniqueId(), amount);
                            messages.send(sender, "limits.increased", "player", target.getName(), "amount", amount);
                        }))
                        .build(),
                PlayerCommandDeclaration.builder(REMOVE)
                        .name("removemaxclaim")
                        .description(Map.of("en", "Lower a player's claim limit", "fr", "Baisser la limite de claims d'un joueur"))
                        .permission("lands.admin")
                        .consoleAllowed(true)
                        .argument(CommandSupport.knownPlayer("player"))
                        .argument(CommandArgument.integer("amount"))
                        .executor(ctx -> withTarget(ctx, true, (sender, target, amount) -> {
                            landRepository.decreaseChunkLimit(target.getUniqueId(), amount);
                            messages.send(sender, "limits.decreased", "player", target.getName(), "amount", amount);
                        }))
                        .build(),
                PlayerCommandDeclaration.builder(GET)
                        .name("getmaxclaim")
                        .description(Map.of("en", "Show a player's claim limit", "fr", "Afficher la limite de claims d'un joueur"))
                        .permission("lands.admin")
                        .consoleAllowed(true)
                        .argument(CommandSupport.knownPlayer("player"))
                        .executor(ctx -> withTarget(ctx, false, (sender, target, amount) ->
                                CompletableFuture.supplyAsync(() -> landRepository.getMaxChunkCount(target.getUniqueId()))
                                        .thenAccept(count -> messages.send(sender, "limits.show",
                                                "player", target.getName(), "amount", count))))
                        .build());
    }

    private interface TargetAction {
        void run(CommandSender sender, OfflinePlayer target, int amount);
    }

    /** {@code amount} at least 1 when {@code withAmount} (Lamp's {@code @Range(min = 1)}). */
    private void withTarget(PlayerCommandContext ctx, boolean withAmount, TargetAction action) {
        CommandSender sender = ctx.getSender();
        int amount = CommandSupport.integer(ctx, "amount", 0);
        if (withAmount && amount < 1) {
            messages.send(sender, "limits.invalid_amount");
            return;
        }
        OfflinePlayer target = support.knownPlayer(sender, ctx, "player");
        if (target != null) action.run(sender, target, amount);
    }
}
