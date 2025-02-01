package fr.iban.lands.service;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.events.PlayerChunkClaimEvent;
import fr.iban.lands.events.PlayerChunkUnclaimEvent;
import fr.iban.lands.events.PlayerLandCreateEvent;
import fr.iban.lands.events.PlayerLandPreCreateEvent;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.*;
import fr.iban.lands.utils.Cuboid;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class LandServiceImpl implements LandService {

    private final LandsPlugin plugin;
    private final LandRepository landRepository;

    public LandServiceImpl(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
    }

    @Override
    public Land createLand(Player creator, String name, LandType type, UUID landOwner) {
        PlayerLandPreCreateEvent event = new PlayerLandPreCreateEvent(creator, name, type);
        if (!event.callEvent()) {
            return null;
        }

        UUID id = UUID.randomUUID();

        Land land = switch (type) {
            case PLAYER -> new PlayerLand(id, landOwner, name);
            case GUILD -> new GuildLand(id, landOwner, name);
            case SYSTEM -> new SystemLand(id, name);
            case SUBLAND -> new SubLand(id, name);
        };

        if (isInvalidName(creator, land)) {
            return null;
        }

        landRepository.addLand(land);

        PlayerLandCreateEvent createEvent = new PlayerLandCreateEvent(creator, land);
        createEvent.callEvent();

        creator.sendMessage("§aLe territoire au nom de " + name + " a été créée.");
        return land;
    }

    @Override
    public void createSubland(Player player, Land superLand, String name) {
        Land land = createLand(player, name, LandType.SUBLAND, superLand.getOwner());

        if (land instanceof SubLand subLand) {
            subLand.setSuperLand(superLand);
            subLand.setCuboid(new Cuboid(Bukkit.getWorlds().get(1), 0, 0, 0, 0, 0, 0), "non défini");
            superLand.getSubLands().put(subLand.getId(), subLand);

            landRepository.updateLand(superLand);
        }
    }

    private boolean isInvalidName(Player player, Land land) {
        if (land.getName().length() > 16) {
            player.sendMessage("§cLe nom du territoire ne doit pas dépasser 16 caractères.");
            return true;
        }

        if (land.getName().contains(" ")) {
            player.sendMessage("§cLe nom du territoire ne doit pas contenir d'espaces.");
            return true;
        }

        // Find a land with the same name, type and owner
        Land sameExistingLand = landRepository.getLands().stream()
                .filter(l -> l.getName().equalsIgnoreCase(land.getName()))
                .filter(l -> !l.getId().equals(land.getId()))
                .filter(l -> l.getType() == land.getType())
                .filter(l -> Objects.equals(l.getOwner(), land.getOwner()))
                .findFirst().orElse(null);

        if (sameExistingLand != null) {
            player.sendMessage("§cVous avez déjà un territoire à ce nom.");
            return true;
        }

        return false;
    }

    @Override
    public void renameLand(Land land, Player player, String name) {
        if (isInvalidName(player, land)) {
            return;
        }

        land.setName(name);
        landRepository.updateLand(land);
        player.sendMessage("§aLe nom du territoire a bien été modifié.");
    }

    @Override
    public void claim(SChunk chunk, Land land) {
        chunk.setCreatedAt(new Date());
        landRepository.addChunk(chunk, land);
    }

    @Override
    public void claim(Player player, List<SChunk> chunks, Land land) {
        int TTChunks = chunks.size();

        AtomicReference<WrappedTask> taskRef = new AtomicReference<>();

        taskRef.set(plugin.getScheduler().runTimerAsync(() -> {
            if (!chunks.isEmpty()) {
                claim(chunks.getFirst(), land);
                chunks.removeFirst();

                if (chunks.size() % 50 == 0) {
                    int loadedChunks = TTChunks - chunks.size();
                    player.sendMessage(String.format("§aProtection des chunks... (%d/%d) [%f%%]", loadedChunks, TTChunks, Math.round(loadedChunks * 100.0F / TTChunks * 10.0F) / 10.0F));
                }
            } else {
                player.sendMessage("§a§lLa sélection a été protégée avec succès.");
                WrappedTask task = taskRef.get();
                if (task != null) {
                    task.cancel();
                }
            }
        }, 0L, 1L));
    }

    @Override
    public void claim(Player player, SChunk chunk, Land land) {
        if (!hasEnouphClaims(player, land)) {
            player.sendMessage("§cVous n'avez plus de claim disponible.");
            return;
        }

        if (landRepository.getLandAt(chunk).equals(landRepository.getWilderness())) {
            if (landRepository.getRemainingChunkCount(land.getOwner()) <= 0) {
                giveClaims(player, land.getOwner(), 1);
            }

            PlayerChunkClaimEvent event = new PlayerChunkClaimEvent(player, chunk);
            if(!event.callEvent()) {
                return;
            }

            claim(chunk, land);
            player.sendActionBar(Component.text("§a§lLe tronçon a bien été claim."));
        } else {
            player.sendActionBar(Component.text("§c§lCe tronçon est déjà claim."));
        }
    }

    @Override
    public void unclaim(Player player, SChunk chunk) {
        Land land = landRepository.getLandAt(chunk);

        if (landRepository.canManageLand(player, land) || plugin.isBypassing(player)) {
            PlayerChunkUnclaimEvent event = new PlayerChunkUnclaimEvent(player, chunk);

            if (!event.callEvent()) {
                return;
            }

            unclaim(chunk);
            player.sendActionBar(Component.text("§a§lLe tronçon a bien été unclaim."));
        } else {
            player.sendActionBar(Component.text("§c§lCe tronçon ne vous appartient pas !"));
        }
    }

    @Override
    public void unclaim(Chunk chunk) {
        unclaim(new SChunk(chunk));
    }

    @Override
    public void unclaim(SChunk chunk) {
        landRepository.removeChunk(chunk);
    }

    private boolean hasEnouphClaims(Player player, Land land) {
        UUID landOwner = land.getOwner();

        if (landOwner == null || plugin.isBypassing(player)) {
            return true;
        }

        int remaining = landRepository.getRemainingChunkCount(landOwner);

        if (land instanceof GuildLand && remaining <= 0) {
            remaining = landRepository.getRemainingChunkCount(player.getUniqueId());
        }

        return remaining > 0;
    }

    @Override
    public void transferClaims(UUID from, UUID to) {
        plugin.runAsyncQueued(() -> {
            int maxChunkCount = landRepository.getMaxChunkCount(from);
            landRepository.increaseChunkLimit(to, maxChunkCount);
            landRepository.setChunkLimit(from, 0);
        });
    }

    @Override
    public void giveClaims(Player player, UUID target, int amount) {
        UUID uuid = player.getUniqueId();

        int remaining = landRepository.getRemainingChunkCount(uuid);

        if (remaining < amount) {
            player.sendMessage("§cVous n'avez pas assez de claims.");
            return;
        }

        landRepository.decreaseChunkLimit(uuid, amount);
        landRepository.increaseChunkLimit(target, amount);

        Player targetPlayer = Bukkit.getPlayer(target);
        if (targetPlayer != null) {
            player.sendMessage("§aVous avez donné " + amount + " claims à " + targetPlayer.getName() + ".");
            targetPlayer.sendMessage("§aVous avez reçu " + amount + " claims de " + player.getName() + ".");
        } else {
            player.sendMessage("§aVous avez donné " + amount + " claims.");
        }
    }

    @Override
    public void ban(Player sender, Land land, UUID uuid) {
        if (sender.getUniqueId().equals(uuid)) {
            sender.sendMessage("§cVous ne pouvez pas vous ban vous même !");
            return;
        }
        if (land.isBanned(uuid)) {
            sender.sendMessage("§cCe joueur est déjà banni.");
            return;
        }

        landRepository.addBan(land, uuid);
        sender.sendMessage("§aLe joueur a bien été banni.");

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            Land landAt = landRepository.getLandAt(player.getChunk());
            if (landAt instanceof PlayerLand pland) {
                if (Objects.equals(pland.getOwner(), sender.getUniqueId())) {
                    player.teleportAsync(plugin.getConfig().getLocation("spawn-location", Objects.requireNonNull(Bukkit.getWorld("world")).getSpawnLocation()));
                }
            }
            player.sendMessage("§aVous avez été banni du territoire " + land.getName() + " par " + sender.getName() + ".");
        }
    }

    @Override
    public void unban(Player sender, Land land, UUID uuid) {
        if (!land.isBanned(uuid)) {
            sender.sendMessage("§cCe joueur n'est pas banni.");
            return;
        }

        landRepository.removeBan(land, uuid);
        sender.sendMessage("§aLe joueur a bien été débanni.");
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.sendMessage("§aVous avez été débanni du territoire " + land.getName() + " par " + sender.getName() + ".");
        }
    }
}
