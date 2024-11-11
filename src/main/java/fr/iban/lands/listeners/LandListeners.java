package fr.iban.lands.listeners;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.events.PlayerChunkClaimEvent;
import fr.iban.lands.events.PlayerChunkUnclaimEvent;
import fr.iban.lands.events.PlayerLandEnterEvent;
import fr.iban.lands.events.PlayerLandFlagChangeEvent;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.GuildLand;
import fr.iban.lands.model.land.Land;
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
            player.sendMessage("§cVous ne pouvez pas entrer dans ce territoire, le propriétaire vous a banni.");
            event.setCancelled(true);
            return;
        }

        Component fromName = Component.text(fromLand.getName()).color(TextColor.fromHexString("#F57C00"));
        UUID ownerFrom = fromLand.getOwner();
        if (ownerFrom != null) {
            String ownerName;
            if (fromLand instanceof GuildLand) {
                ownerName = "Guilde - " + ((GuildLand) fromLand).getGuildName();
            } else {
                ownerName = Bukkit.getOfflinePlayer(ownerFrom).getName();
            }
            fromName = fromName.append(Component.text(" ☗ " + ownerName).color(TextColor.fromHexString("#FFA726")));
        }

        Component toName = Component.text(toLand.getName()).color(TextColor.fromHexString("#AFB42B"));
        UUID ownerTo = toLand.getOwner();

        if (ownerTo != null) {
            String ownerName;
            if (toLand instanceof GuildLand toGuildLand) {
                ownerName = "Guilde - " + toGuildLand.getGuildName();
            } else {
                ownerName = Bukkit.getOfflinePlayer(ownerTo).getName();
            }

            toName = toName.append(Component.text(" ☗ " + ownerName).color(TextColor.fromHexString("#D4E157")));
        }

        Component finalComponent = fromName.append(Component.text(" §8➜ ").append(toName)).decorate(TextDecoration.BOLD);

        player.sendActionBar(finalComponent);

        if (fromLand.hasFlag(Flag.INVISIBLE)) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }

        if (toLand.hasFlag(Flag.INVISIBLE)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1));
        }
    }

    @EventHandler
    public void onFlagChange(PlayerLandFlagChangeEvent event) {
        Player player = event.getPlayer();

        if (event.getFlag() == Flag.SILENT_MOBS) {
            if (event.getLand() instanceof SubLand) {
                player.sendMessage("§cFlag non supporté sur les sous territoires.");
                event.setCancelled(true);
                return;
            }

            AtomicInteger count = new AtomicInteger();
            player.sendMessage("§aChangement d'état des mobs...");
            for (SChunk schunk : plugin.getLandRepository().getChunks(event.getLand())) {
                schunk.getChunkAsync().thenAccept(chunk -> {
                    for (Entity entity : chunk.getEntities()) {
                        if (entity instanceof Player) {
                            continue;
                        }

                        entity.setSilent(event.getNewState());
                        count.getAndIncrement();
                    }
                });
            }
            player.sendMessage("§a" + count.get() + " mobs sont maintenant " + (event.getNewState() ? "silencieux" : "bruyants"));
        }
    }

    @EventHandler
    public void onClaim(PlayerChunkClaimEvent event) {
        plugin.getSeeClaims().values().forEach(SeeClaims::forceUpdate);
    }

    @EventHandler
    public void onUnclaim(PlayerChunkUnclaimEvent event) {
        plugin.getSeeClaims().values().forEach(SeeClaims::forceUpdate);
    }
}
