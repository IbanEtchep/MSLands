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
import fr.iban.lands.messages.LandMessages;
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
    private final LandMessages messages;

    public LandServiceImpl(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
        this.messages = plugin.getMessages();
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

        if (isInvalidName(creator, land, name)) {
            return null;
        }

        landRepository.addLand(land);

        PlayerLandCreateEvent createEvent = new PlayerLandCreateEvent(creator, land);
        createEvent.callEvent();

        messages.send(creator, "land.created", "name", name);
        return land;
    }

    @Override
    public void createSubland(Player player, Land superLand, String name) {
        Land land = createLand(player, name, LandType.SUBLAND, superLand.getOwner());

        if (land instanceof SubLand subLand) {
            subLand.setSuperLand(superLand);
            // No area until the owner selects one (MSLands put a placeholder in the second world).
            subLand.setCuboid(null, null);
            superLand.getSubLands().put(subLand.getId(), subLand);

            // Its row of sublands, now that it knows its land (addLand ran before).
            landRepository.updateLand(subLand);
        }
    }

    /** {@code name} for {@code land} (its type and owner), as MSLands checked it; the reason sent when invalid. */
    private boolean isInvalidName(Player player, Land land, String name) {
        if (name == null || name.isBlank()) {
            messages.send(player, "land.name.empty");
            return true;
        }

        if (name.length() > 16) {
            messages.send(player, "land.name.too_long");
            return true;
        }

        if (name.contains(" ")) {
            messages.send(player, "land.name.spaces");
            return true;
        }

        // Find a land with the same name, type and owner
        Land sameExistingLand = landRepository.getLands().stream()
                .filter(l -> l.getName() != null && l.getName().equalsIgnoreCase(name))
                .filter(l -> !l.getId().equals(land.getId()))
                .filter(l -> l.getType() == land.getType())
                .filter(l -> Objects.equals(l.getOwner(), land.getOwner()))
                .findFirst().orElse(null);

        if (sameExistingLand != null) {
            messages.send(player, "land.name.taken");
            return true;
        }

        return false;
    }

    @Override
    public void renameLand(Land land, Player player, String name) {
        name = name == null ? null : name.trim();
        // The new name, not the current one (MSLands checked the current one).
        if (isInvalidName(player, land, name)) {
            return;
        }

        land.setName(name);
        landRepository.updateLand(land);
        messages.send(player, "land.renamed");
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
                    messages.send(player, "claim.progress", "done", loadedChunks, "total", TTChunks,
                            "percent", Math.round(loadedChunks * 100.0F / TTChunks * 10.0F) / 10.0F);
                }
            } else {
                messages.send(player, "claim.selection_done");
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
            messages.send(player, "claim.none_left");
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
            messages.actionBar(player, "claim.done");
        } else {
            messages.actionBar(player, "claim.taken");
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
            messages.actionBar(player, "unclaim.done");
        } else {
            messages.actionBar(player, "unclaim.not_yours");
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
            messages.send(player, "claims.not_enough");
            return;
        }

        landRepository.decreaseChunkLimit(uuid, amount);
        landRepository.increaseChunkLimit(target, amount);

        Player targetPlayer = Bukkit.getPlayer(target);
        if (targetPlayer != null) {
            messages.send(player, "claims.given", "amount", amount, "player", targetPlayer.getName());
            messages.send(targetPlayer, "claims.received", "amount", amount, "player", player.getName());
        } else {
            messages.send(player, "claims.given_offline", "amount", amount);
        }
    }

    @Override
    public void ban(Player sender, Land land, UUID uuid) {
        if (sender.getUniqueId().equals(uuid)) {
            messages.send(sender, "ban.self");
            return;
        }
        if (land.isBanned(uuid)) {
            messages.send(sender, "ban.already");
            return;
        }

        landRepository.addBan(land, uuid);
        messages.send(sender, "ban.done");

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            Land landAt = landRepository.getLandAt(player.getChunk());
            if (landAt instanceof PlayerLand pland) {
                if (Objects.equals(pland.getOwner(), sender.getUniqueId())) {
                    player.teleportAsync(plugin.getConfig().getLocation("spawn-location", Objects.requireNonNull(Bukkit.getWorld("world")).getSpawnLocation()));
                }
            }
            messages.send(player, "ban.notify", "land", land.getName(), "player", sender.getName());
        }
    }

    @Override
    public void unban(Player sender, Land land, UUID uuid) {
        if (!land.isBanned(uuid)) {
            messages.send(sender, "unban.not_banned");
            return;
        }

        landRepository.removeBan(land, uuid);
        messages.send(sender, "unban.done");
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            messages.send(player, "unban.notify", "land", land.getName(), "player", sender.getName());
        }
    }
}
