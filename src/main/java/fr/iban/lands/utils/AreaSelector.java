package fr.iban.lands.utils;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class AreaSelector {

    private final LandsPlugin plugin;
    private final LandRepository landRepository;
    private final LandService landService;

    private final Player player;

    private Location pos1;
    private Location pos2;

    private final Runnable quitCallback;
    private WrappedTask particleTask;

    private final Land land;

    public AreaSelector(Player player, Land land, LandsPlugin plugin, Runnable quitCallback) {
        this.land = land;
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
        this.landService = plugin.getLandService();
        this.player = player;
        this.quitCallback = quitCallback;
    }

    public void startSelecting() {
        LandMessages messages = plugin.getMessages();
        messages.send(player, "selection.help");
        if (player.hasPermission("lands.admin")) messages.send(player, "selection.help_admin");
        plugin.getChatInputs().await(player, text -> {
            if (text.equalsIgnoreCase("pos1")) {
                cancelTask();
                pos1 = player.getLocation();
                messages.send(player, "selection.pos1");
                showSize();
            } else if (text.equalsIgnoreCase("pos2")) {
                cancelTask();
                pos2 = player.getLocation();
                messages.send(player, "selection.pos2");
                showSize();
            } else if (text.equalsIgnoreCase("claim")) {
                Cuboid cuboid = getCuboid();
                verif(cuboid, true).thenAcceptAsync(valid -> {
                    if (valid) {
                        landService.claim(player, Objects.requireNonNull(cuboid).getSChunks(), land);
                    }
                });
            } else if (text.equalsIgnoreCase("unclaim")) {
                Cuboid cuboid = getCuboid();
                verif(cuboid, false).thenAcceptAsync(valid -> {
                    if (valid) {
                        for (SChunk chunk : Objects.requireNonNull(cuboid).getSChunks()) {
                            // Only this land's chunks: the check allowed no other.
                            if (landRepository.getLandAt(chunk).equals(land)) landService.unclaim(chunk);
                        }
                        messages.send(player, "selection.unclaimed");
                    }
                });
            } else if (player.hasPermission("lands.admin") && text.equalsIgnoreCase("forceunclaim")) {
                if (arePosSet()) {
                    for (SChunk chunk : Objects.requireNonNull(getCuboid()).getSChunks()) {
                        landService.unclaim(chunk);
                    }
                    messages.send(player, "selection.unclaimed");
                } else {
                    messages.send(player, "selection.both_missing");
                }
            } else if (text.toLowerCase(java.util.Locale.ROOT).startsWith("quit")) {
                plugin.getChatInputs().cancel(player);
                cancelTask();
                quitCallback.run();
            } else {
                messages.send(player, "selection.invalid");
                startSelecting();
            }
        });
    }

    private void showSize() {
        Cuboid cuboid = getCuboid();
        if (cuboid != null) {
            plugin.getMessages().send(player, "selection.contains", "count", cuboid.getSChunks().size());
            showParticules(cuboid);
        }
    }

    private void cancelTask() {
        if (particleTask != null) {
            particleTask.cancel();
        }
    }

    private boolean arePosSet() {
        return pos1 != null && pos2 != null;
    }

    private Cuboid getCuboid() {
        if (!arePosSet()) {
            return null;
        }

        Cuboid cuboid = new Cuboid(pos1, pos2);

        return new Cuboid(
                cuboid.getLowerNE().getChunk()
                        .getBlock(0, cuboid.getWorld().getMinHeight(), 0)
                        .getLocation(),
                cuboid.getUpperSW().getChunk()
                        .getBlock(15, cuboid.getWorld().getMaxHeight() - 1, 15)
                        .getLocation());
    }

    private CompletableFuture<Boolean> verif(Cuboid cuboid, boolean claim) {
        plugin.getMessages().send(player, "selection.verifying");
        return CompletableFuture.supplyAsync(() -> {
            if (cuboid == null) {
                if (pos1 == null) {
                    plugin.getMessages().send(player, "selection.pos1_missing");
                } else {
                    plugin.getMessages().send(player, "selection.pos2_missing");
                }
                return false;
            }
            int unclaimCount = 0;
            int claimCount = 0;
            if (cuboid.getSizeX() > 3000 || cuboid.getSizeZ() > 3000) {
                plugin.getMessages().send(player, "selection.too_big");
                return false;
            }
            for (SChunk chunk : cuboid.getSChunks()) {
                Land chunkLand = landRepository.getLandAt(chunk);
                Land wilderness = landRepository.getWilderness();

                if (!chunkLand.equals(wilderness)) {
                    if (!landRepository.getLandAt(chunk).equals(land)) {
                        plugin.getMessages().send(player, "selection.foreign_chunks", "land", land.getName());
                        return false;
                    } else {
                        unclaimCount++;
                    }
                } else {
                    claimCount++;
                }
            }
            if (claim) {
                if (claimCount == 0) {
                    plugin.getMessages().send(player, "selection.nothing_to_claim");
                    return false;
                }

                int remaining = landRepository.getRemainingChunkCount(player.getUniqueId());
                if (claimCount > remaining) {
                    plugin.getMessages().send(player, "selection.too_many", "count", claimCount, "remaining", remaining);
                    return false;
                }

            } else {
                if (unclaimCount == 0) {
                    plugin.getMessages().send(player, "selection.nothing_to_unclaim");
                    return false;
                }
            }

            return true;
        });
    }

    private void showParticules(Cuboid cuboid) {
        List<Location> edgeLocations = cuboid.getCubeEdgeLocations(1);

        if (particleTask != null) {
            particleTask.cancel();
        }

        particleTask = plugin.getScheduler().runTimer(() -> {
            for (Location location : edgeLocations) {
                showParticle(location);
            }
        }, 1L, 10L);
    }

    private void showParticle(Location loc) {
        player.spawnParticle(Particle.HAPPY_VILLAGER, loc, 1);
    }
}
