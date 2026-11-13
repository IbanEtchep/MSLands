package fr.iban.lands.listeners;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.events.PlayerChunkClaimEvent;
import fr.iban.lands.events.PlayerChunkUnclaimEvent;
import fr.iban.lands.events.PlayerLandEnterEvent;
import fr.iban.lands.events.PlayerLandFlagChangeEvent;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.GuildLand;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.LandEnterCommand;
import fr.iban.lands.model.land.SubLand;
import fr.iban.lands.utils.SeeClaims;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class LandListeners implements Listener {

    private final LandsPlugin plugin;
    private final LandRepository landRepository;

    public LandListeners(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
    }

    @EventHandler
    public void onEnter(PlayerLandEnterEvent event) {
        Player player = event.getPlayer();
        Land toLand = event.getToLand();
        Land fromLand = event.getFromLand();

        if (fromLand.equals(toLand)) return;

        if (toLand.isBanned(player.getUniqueId())
                && !plugin.isBypassing(player)
                && !player.hasPermission("lands.bypass.ban")) {
            plugin.getMessages().send(player, "ban.enter_denied");
            event.setCancelled(true);
            return;
        }

        Component enterActionbarMessage = getLandEnterActionBarMessage(player, fromLand, toLand);
        if(enterActionbarMessage != null) {
            player.sendActionBar(enterActionbarMessage);
        }

        if (fromLand.hasFlag(Flag.INVISIBLE)) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }

        if (toLand.hasFlag(Flag.INVISIBLE)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1));
        }

        for (LandEnterCommand command : toLand.getEnterCommands()) {
            if (command.isAsConsole()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.getCommand().replace("%player%", player.getName()));
            } else {
                player.performCommand(command.getCommand().replace("%player%", player.getName()));
            }
        }
    }

    @EventHandler
    public void onFlagChange(PlayerLandFlagChangeEvent event) {
        Player player = event.getPlayer();

        if (event.getFlag() == Flag.SILENT_MOBS) {
            if (event.getLand() instanceof SubLand) {
                plugin.getMessages().send(player, "flag.silent_mobs.sublands");
                event.setCancelled(true);
                return;
            }

            plugin.getMessages().send(player, "flag.silent_mobs.changing");
            for (SChunk schunk : plugin.getLandRepository().getChunks(event.getLand())) {
                schunk.getChunkAsync().thenAccept(chunk -> {
                    for (Entity entity : chunk.getEntities()) {
                        if (entity instanceof Player) {
                            continue;
                        }

                        entity.setSilent(event.getNewState());
                    }
                });
            }
            // The chunks load asynchronously: MSLands counted the mobs before any was changed (always 0).
            plugin.getMessages().send(player, event.getNewState() ? "flag.silent_mobs.silent" : "flag.silent_mobs.noisy");
        }
    }

    @EventHandler
    public void onClaim(PlayerChunkClaimEvent event) {
        plugin.getScheduler().runLater(() -> plugin.getSeeClaims().values().forEach(SeeClaims::forceUpdate), 1);
    }

    @EventHandler
    public void onUnclaim(PlayerChunkUnclaimEvent event) {
        plugin.getScheduler().runLater(() -> plugin.getSeeClaims().values().forEach(SeeClaims::forceUpdate), 1);
    }

    @Nullable
    private Component getLandEnterActionBarMessage(Player player, Land fromLand, Land toLand) {
        if (toLand.hasFlag(Flag.SILENT_ENTER_QUIT) || fromLand.hasFlag(Flag.SILENT_ENTER_QUIT)) {
            return null;
        }
        LandMessages messages = plugin.getMessages();
        return messages.component(player, "enter.bar",
                "from", messages.landName(player, fromLand), "from_owner", ownerSuffix(player, fromLand),
                "to", messages.landName(player, toLand), "to_owner", ownerSuffix(player, toLand));
    }

    /** " ☗ owner", or nothing for a land without owner. */
    private String ownerSuffix(Player player, Land land) {
        UUID owner = land.getOwner();
        if (owner == null) return "";
        String name = land instanceof GuildLand guildLand
                ? plugin.getMessages().text(player, "guild.owner", "Guilde - " + guildLand.getGuildName(), "guild", guildLand.getGuildName())
                : Bukkit.getOfflinePlayer(owner).getName();
        return " ☗ " + (name != null ? name : owner.toString());
    }
}
