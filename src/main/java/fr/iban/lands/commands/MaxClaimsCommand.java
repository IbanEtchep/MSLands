package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.api.LandRepository;
import org.bukkit.OfflinePlayer;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Range;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.concurrent.CompletableFuture;

public class MaxClaimsCommand {

    private final LandRepository landRepository;
    private final LandMessages messages;

    public MaxClaimsCommand(LandsPlugin plugin) {
        this.landRepository = plugin.getLandRepository();
        this.messages = plugin.getMessages();
    }

    @Command("addmaxclaim")
    @CommandPermission("lands.admin")
    public void addMaxClaim(BukkitCommandActor player, OfflinePlayer target, @Range(min = 1) int amount) {
        landRepository.increaseChunkLimit(target.getUniqueId(), amount);
        messages.send(player.sender(), "limits.increased", "player", target.getName(), "amount", amount);
    }

    @Command("removemaxclaim")
    @CommandPermission("lands.admin")
    public void removeMaxClaim(BukkitCommandActor player, OfflinePlayer target, @Range(min = 1) int amount) {
        landRepository.decreaseChunkLimit(target.getUniqueId(), amount);
        messages.send(player.sender(), "limits.decreased", "player", target.getName(), "amount", amount);
    }

    @Command("getMaxClaim")
    @CommandPermission("lands.admin")
    public void getMaxClaim(BukkitCommandActor sender, OfflinePlayer target) {
        CompletableFuture<Integer> maxClaims = CompletableFuture.supplyAsync(() -> landRepository.getMaxChunkCount(target.getUniqueId()));
        maxClaims.thenAccept(count -> messages.send(sender.sender(), "limits.show", "player", target.getName(), "amount", count));
    }
}
