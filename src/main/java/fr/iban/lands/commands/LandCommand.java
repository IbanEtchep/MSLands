package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.LandEnterCommand;
import fr.iban.lands.model.land.SystemLand;
import fr.iban.lands.utils.DateUtils;
import fr.iban.lands.utils.LandMap;
import fr.iban.lands.utils.SeeClaims;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import revxrsal.commands.annotation.*;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Command({"land", "l"})
public class LandCommand {

    private final LandRepository landRepository;
    private final LandsPlugin plugin;
    private final boolean playerLandsEnabled;
    private final LandService landService;
    private final LandMessages messages;

    public LandCommand(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
        this.landService = plugin.getLandService();
        this.messages = plugin.getMessages();
        this.playerLandsEnabled = plugin.getConfig().getBoolean("players-lands-enabled");
    }

    @CommandPlaceholder
    public void onCommand(Player player) {
        help(player);
    }

    @Subcommand("claim")
    public void claim(Player player, @Optional Land withLand) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        if (withLand == null) {
            Land firstLand = landRepository.getLands(player.getUniqueId(), LandType.PLAYER)
                    .stream().findFirst().orElse(null);

            if (firstLand == null) {
                messages.send(player, "land.none");
                return;
            }

            withLand = firstLand;
        }

        landService.claim(player, new SChunk(player.getLocation().getChunk()), withLand);
    }

    @Subcommand("unclaim")
    public void unclaim(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        landService.unclaim(player, new SChunk(player.getLocation().getChunk()));
    }

    @Subcommand("forceunclaim")
    @CommandPermission("lands.admin")
    @SecretCommand
    public void forceunclaim(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        landService.unclaim(player.getLocation().getChunk());
        messages.send(player, "unclaim.forced");
    }

    @Subcommand("kick")
    public void kick(Player player, Player target) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        if (!target.getUniqueId().equals(player.getUniqueId())) {
            Land land = landRepository.getLandAt(target.getLocation());
            if (landRepository.canManageLand(player, land)) {
                target.teleportAsync(
                        plugin.getConfig().getLocation("spawn-location", Objects.requireNonNull(Bukkit.getWorld("world")).getSpawnLocation()));
                messages.send(target, "kick.notify", "player", player.getName());
                messages.actionBar(player, "kick.done");
            } else {
                messages.send(player, "kick.denied");
            }
        } else {
            messages.send(player, "kick.self");
        }
    }

    @Subcommand("bypass")
    @CommandPermission("lands.bypass")
    public void bypass(Player player) {
        plugin.setBypassing(player.getUniqueId(), !plugin.getBypass().contains(player.getUniqueId()));
        messages.send(player, plugin.isBypassing(player) ? "bypass.on" : "bypass.off");
    }

    @Subcommand("create")
    @Cooldown(value = 10)
    public void create(Player player, @Optional String name) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        if (name == null) {
            messages.send(player, "create.usage");
        } else {
            landService.createLand(player, name, LandType.PLAYER, player.getUniqueId());
        }
    }

    @Subcommand("delete")
    public void create(Player player, Land land) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {

            messages.send(player, "disabled");
            return;
        }

        if (land == null) {
            messages.send(player, "land.not_found", "name", "");
        } else {
            plugin.getMenus().openDeleteConfirm(player, land);
        }
    }

    @Subcommand("claimat")
    @SecretCommand
    public void claimat(Player player, World world, int X, int Z) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {

            messages.send(player, "disabled");
            return;
        }

        LandMap map = plugin.getLandMap();
        if (!map.getLandMapSelection().isEmpty()
                && map.getLandMapSelection().containsKey(player.getUniqueId())) {
            Land land = map.getLandMapSelection().get(player.getUniqueId());
            if (land != null) {
                SChunk sChunk = new SChunk(LandsPlugin.getInstance().getServerName(), world.getName(), X, Z);
                landService.claim(player, sChunk, land);
                map.display(player, land);
            }
        }
    }

    @Subcommand("unclaimat")
    @SecretCommand
    public void unclaimat(Player player, World world, int X, int Z) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        LandMap map = plugin.getLandMap();
        UUID uuid = player.getUniqueId();

        if (!map.getLandMapSelection().isEmpty() && map.getLandMapSelection().containsKey(uuid)) {
            Land land = map.getLandMapSelection().get(uuid);

            if (land != null) {
                SChunk sChunk = new SChunk(LandsPlugin.getInstance().getServerName(), world.getName(), X, Z);

                // A system land's chunk needs lands.admin; any other goes through the owner's checks
                // (MSLands unclaimed twice here).
                if (land instanceof SystemLand && player.hasPermission("lands.admin")) {
                    landService.unclaim(sChunk);
                } else {
                    landService.unclaim(player, sChunk);
                }
                map.display(player, land);
                messages.actionBar(player, "unclaim.done");
            }
        }
    }

    @Subcommand("map")
    public void map(Player player, @Optional Land land) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        plugin.getLandMap().display(player, land);
    }

    @Subcommand("seeclaims")
    public void seeclaims(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!plugin.getSeeClaims().containsKey(uuid)) {
            SeeClaims sc = new SeeClaims(player, plugin);
            plugin.getSeeClaims().put(player.getUniqueId(), sc);
            sc.showWalls();
            messages.send(player, "seeclaims.on");
        } else {
            SeeClaims sc = plugin.getSeeClaims().get(uuid);
            sc.stop();
            plugin.getSeeClaims().remove(uuid);
            messages.send(player, "seeclaims.off");
        }
    }

    @Subcommand("menu")
    public void menu(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {

            messages.send(player, "disabled");
            return;
        }

        player.performCommand("lands");
    }

    @Subcommand("admin setspawn")
    @CommandPermission("lands.admin")
    public void setSpawnLocation(Player player) {
        plugin.getConfig().set("spawn-location", player.getLocation());
        plugin.saveConfig();
        messages.send(player, "admin.setspawn");
    }

    @Subcommand("admin debug")
    @CommandPermission("lands.admin")
    public void debugMode(Player player) {
        plugin.setDebugging(player.getUniqueId(), !plugin.isInDebugMode(player.getUniqueId()));
        messages.send(player, plugin.isInDebugMode(player) ? "admin.debug.on" : "admin.debug.off");
    }

    @Subcommand("admin chunkinfo")
    @CommandPermission("lands.admin")
    public void chunkInfo(Player player) {
        Chunk chunk = player.getChunk();
        SChunk sChunk = new SChunk(chunk);
        Map.Entry<SChunk, Land> entry = landRepository.getChunks().entrySet().stream()
                .filter(e -> e.getKey().equals(sChunk))
                .findFirst()
                .orElse(null);

        if (entry != null) {
            SChunk schunk = entry.getKey();
            Land land = entry.getValue();
            messages.send(player, "admin.chunkinfo.header", "x", schunk.getX(), "z", schunk.getZ());
            messages.send(player, "admin.chunkinfo.type", "type", land.getType().toString());
            messages.send(player, "admin.chunkinfo.land", "land", messages.landName(player, land));
            messages.send(player, "admin.chunkinfo.claimed_at", "date",
                    schunk.getCreatedAt() != null ? DateUtils.format(schunk.getCreatedAt()) : "?");
        } else {
            messages.send(player, "admin.chunkinfo.none");
        }
    }

    @Subcommand("admin setDefaultWorldClaim")
    @CommandPermission("lands.admin")
    public void setDefaultWorldClaim(Player player, World world, Land land) {
        landRepository.setDefaultWorldLand(world, land);
        messages.send(player, "admin.default_world_land", "world", world.getName(), "land", land.getName());
    }

    @Subcommand("help")
    public void help(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        messages.send(player, "help.text");
    }

    @Subcommand("giveclaims")
    private void giveClaims(Player player, OfflinePlayer target, int amount) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        landService.giveClaims(player, target.getUniqueId(), amount);
    }

    //land admin effect add/remove effecttype aplifier=1
    @Subcommand("admin effect add")
    @CommandPermission("lands.admin")
    public void addEffect(Player player, Land land, String effectType, @Default("0") int amplifier) {
        NamespacedKey potionKey = NamespacedKey.fromString(effectType.toLowerCase(Locale.ROOT));

        if(potionKey == null || Registry.EFFECT.get(potionKey) == null) {
            messages.send(player, "admin.effect.unknown");
            return;
        }

        PotionEffectType potionEffectType = Registry.EFFECT.get(potionKey);
        landRepository.addEffect(land, potionEffectType, amplifier);
        messages.send(player, "admin.effect.added");
    }

    @Subcommand("admin effect remove")
    @CommandPermission("lands.admin")
    public void removeEffect(Player player, Land land, String effectType) {
        NamespacedKey potionKey = NamespacedKey.fromString(effectType.toLowerCase(Locale.ROOT));

        if(potionKey == null || Registry.EFFECT.get(potionKey) == null) {
            messages.send(player, "admin.effect.unknown");
            return;
        }

        PotionEffectType potionEffectType = Registry.EFFECT.get(potionKey);
        landRepository.removeEffect(land, potionEffectType);
        messages.send(player, "admin.effect.removed");
    }

    @Subcommand("admin effect list")
    @CommandPermission("lands.admin")
    public void listEffects(Player player, Land land) {
        messages.send(player, "admin.effect.list_header", "land", land.getName());
        land.getEffects().forEach(potionEffect -> messages.send(player, "admin.effect.list_line",
                "effect", potionEffect.getType().getKey(), "amplifier", potionEffect.getAmplifier()));
    }

    @Subcommand("admin command add")
    @CommandPermission("lands.admin")
    @Usage("/land admin command add <land> <asConsole> <command>")
    public void addCommand(Player player, Land land, boolean asConsole, String command) {
        landRepository.addCommand(land, new LandEnterCommand(command, asConsole));
        messages.send(player, "admin.command.added");
    }

    @Subcommand("admin command remove")
    @CommandPermission("lands.admin")
    @Usage("/land admin command remove <land_id> <command_id>")
    public void removeCommand(Player player, String landIdString, String commandIdString) {
        UUID landId;
        UUID commandId;
        try {
            landId = UUID.fromString(landIdString);
            commandId = UUID.fromString(commandIdString);
        }catch (IllegalArgumentException e) {
            messages.send(player, "admin.command.invalid_id");
            return;
        }

        Land land = landRepository.getLandById(landId).orElse(null);

        if(land == null) {
            messages.send(player, "land.not_found", "name", landIdString);
            return;
        }

        LandEnterCommand command = land.getEnterCommands().stream()
                .filter(c -> c.getUniqueId().equals(commandId))
                .findFirst().orElse(null);

        if(command == null) {
            messages.send(player, "admin.command.not_found");
            return;
        }

        landRepository.removeCommand(land, command);

        messages.send(player, "admin.command.removed");
    }

    @Subcommand("admin command list")
    @CommandPermission("lands.admin")
    @Usage("/land admin command list <land>")
    public void listCommands(Player player, Land land) {
        messages.send(player, "admin.command.list_header", "land", land.getName());
        String console = messages.text(player, "admin.command.console", "[Console]");
        land.getEnterCommands().forEach(command -> messages.send(player, "admin.command.list_line",
                "command", command.getCommand(), "console", command.isAsConsole() ? console : "",
                "land_id", land.getId(), "command_id", command.getUniqueId()));
    }
}
