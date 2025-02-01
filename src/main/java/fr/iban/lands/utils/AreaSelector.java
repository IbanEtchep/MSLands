package fr.iban.lands.utils;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import fr.iban.bukkitcore.CoreBukkitPlugin;
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
        player.sendMessage(getHelpText());
        CoreBukkitPlugin core = CoreBukkitPlugin.getInstance();
        core.getTextInputs().put(player.getUniqueId(), component -> {
            String text = ChatUtils.toPlainText(component);

            if (text.equalsIgnoreCase("pos1")) {
                cancelTask();
                pos1 = player.getLocation();
                player.sendMessage("§a§lPosition 1 définie.");

                Cuboid cuboid = getCuboid();
                if (cuboid != null) {
                    player.sendMessage(
                            "§a§lVotre selection contient " + cuboid.getChunks().size() + " tronçons.");
                    showParticules(cuboid);
                }

            } else if (text.equalsIgnoreCase("pos2")) {
                cancelTask();
                pos2 = player.getLocation();
                player.sendMessage("§a§lPosition 2 définie.");

                Cuboid cuboid = getCuboid();
                if (cuboid != null) {
                    player.sendMessage(
                            "§a§lVotre selection contient " + cuboid.getChunks().size() + " tronçons.");
                    showParticules(cuboid);
                }

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
                        for (SChunk chunk : Objects.requireNonNull(getCuboid()).getSChunks()) {
                            landService.unclaim(chunk);
                        }

                        player.sendMessage("§aLa selection a été unclaim avec succès.");
                    }
                });
            } else if (player.hasPermission("lands.admin")
                    && text.equalsIgnoreCase("forceunclaim")) {
                if (arePosSet()) {
                    for (Chunk chunk : Objects.requireNonNull(getCuboid()).getChunks()) {
                        landService.unclaim(chunk);
                    }
                    player.sendMessage("§a§lLa selection a été unclaim avec succès.");
                } else {
                    player.sendMessage("§c§lIl faut définir les deux positions !");
                }
            } else if (text.startsWith("quit")) {
                quitCallback.run();
                core.getTextInputs().remove(player.getUniqueId());
                cancelTask();
            } else {
                player.sendMessage("§c§lLe texte que vous avez saisi est invalide.");
                startSelecting();
            }
        });
    }

    private void cancelTask() {
        if (particleTask != null) {
            particleTask.cancel();
        }
    }

    private Component getHelpText() {
        Component pos1 =
                Component.text("§6§lpos1")
                        .hoverEvent(HoverEvent.showText(Component.text("§aCliquez pour définir la position 1")))
                        .clickEvent(ClickEvent.runCommand("/sayinchat pos1"));
        Component pos2 =
                Component.text("§6§lpos2")
                        .hoverEvent(HoverEvent.showText(Component.text("§aCliquez pour définir la position 2")))
                        .clickEvent(ClickEvent.runCommand("/sayinchat pos2"));
        Component claim =
                Component.text("§a§lclaim")
                        .hoverEvent(HoverEvent.showText(Component.text("§aCliquez pour claim la sélection")))
                        .clickEvent(ClickEvent.runCommand("/sayinchat claim"));
        Component unclaim =
                Component.text("§c§lunclaim")
                        .hoverEvent(HoverEvent.showText(Component.text("§aCliquez pour unclaim la sélection")))
                        .clickEvent(ClickEvent.runCommand("/sayinchat unclaim"));
        Component forceunclaim =
                Component.text("§c§lforceunclaim")
                        .hoverEvent(
                                HoverEvent.showText(Component.text("§aCliquez TOUS les chunks de la sélection.")))
                        .clickEvent(ClickEvent.runCommand("/sayinchat forceunclaim"));
        Component quit =
                Component.text("§c§lquitter")
                        .hoverEvent(
                                HoverEvent.showText(Component.text("§aCliquez pour quitter le mode sélection")))
                        .clickEvent(ClickEvent.runCommand("/sayinchat quit"));

        Component helpText =
                Component.text(
                                "§e§lLes mots clés pour la création d'une sélection sont (hover-clic possible) : ")
                        .append(pos1)
                        .append(Component.text(" §e§l, "))
                        .append(pos2)
                        .append(Component.text(" §e§l, "))
                        .append(claim)
                        .append(Component.text(" §e§l, "))
                        .append(unclaim)
                        .append(Component.text(" §e§let "));

        if (player.hasPermission("lands.admin")) {
            helpText = helpText.append(forceunclaim).append(Component.text(" §e§l, "));
        }

        helpText = helpText.append(quit).append(Component.text("."));
        return helpText;
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
        player.sendMessage("§a§lVérification de la sélection...");
        return CompletableFuture.supplyAsync(() -> {
            if (cuboid == null) {
                if (pos1 == null) {
                    player.sendMessage("§c§lLa position 1 n'a pas encore été définie !");
                } else {
                    player.sendMessage("§c§lLa position 2 n'a pas encore été définie !");
                }
                return false;
            }
            int unclaimCount = 0;
            int claimCount = 0;
            if (cuboid.getSizeX() > 3000 || cuboid.getSizeZ() > 3000) {
                player.sendMessage("§c§lLa selection est trop grande !");
            }
            for (SChunk chunk : cuboid.getSChunks()) {
                Land chunkLand = landRepository.getLandAt(chunk);
                Land wilderness = landRepository.getWilderness();

                if (!chunkLand.equals(wilderness)) {
                    if (!landRepository.getLandAt(chunk).equals(land)) {
                        player.sendMessage("§c§lLa selection contient des tronçons qui ne sont pas vides et n'appartiennent pas au territoire " + land.getName() + ".");
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
                    player.sendMessage("§c§lIl n'y a pas de tronçons à claim dans la selection.");
                    return false;
                }

                int remaining = landRepository.getRemainingChunkCount(player.getUniqueId());
                if (claimCount > remaining) {
                    player.sendMessage("§c§lVous essayez de claim " + claimCount + " tronçons alors qu'il ne vous en reste que " + remaining + " de libre.");
                    return false;
                }

            } else {
                if (unclaimCount == 0) {
                    player.sendMessage("§c§lIl n'y a pas de tronçons à unclaim dans la selection.");
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
