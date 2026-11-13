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
import net.artisanmc.modules.api.playercommand.CommandArgument;
import net.artisanmc.modules.api.playercommand.PlayerCommandContext;
import net.artisanmc.modules.api.playercommand.PlayerCommandDeclaration;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /land} (alias {@code /l}), declared through Artisan's player commands: registered by
 * {@link fr.iban.lands.menu.LandsModule}, it is the file {@code commands/land.yaml} of the module's
 * folder, which the admin edits in the editor. Same sub-commands and permissions as with Lamp.
 */
public class LandCommand {

    public static final String ROOT = "lands:land";
    private static final long CREATE_COOLDOWN_MILLIS = 10_000;


    private final LandRepository landRepository;
    private final LandsPlugin plugin;
    private final boolean playerLandsEnabled;
    private final LandService landService;
    private final LandMessages messages;
    private final CommandSupport support;
    /** Last {@code /land create} of each player (Lamp's {@code @Cooldown(10)}). */
    private final Map<UUID, Long> lastCreate = new ConcurrentHashMap<>();

    public LandCommand(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
        this.landService = plugin.getLandService();
        this.messages = plugin.getMessages();
        this.support = new CommandSupport(plugin);
        this.playerLandsEnabled = plugin.getConfig().getBoolean("players-lands-enabled");
    }

    /** The whole tree, registered once with {@code api.getPlayerCommands()}. */
    public PlayerCommandDeclaration declaration() {
        return PlayerCommandDeclaration.builder(ROOT)
                .name("land")
                .aliases("l")
                .description(Map.of("en", "Lands", "fr", "Territoires"))
                .executor(CommandSupport.player((p, ctx) -> onCommand(p)))
                .child(sub("help", "List the land commands", "Lister les commandes des territoires")
                        .executor(CommandSupport.player((p, ctx) -> help(p))).build())
                .child(sub("menu", "Open your lands", "Ouvrir vos territoires")
                        .executor(CommandSupport.player((p, ctx) -> menu(p))).build())
                .child(sub("create", "Create a land", "Créer un territoire")
                        .argument(CommandArgument.string("name").optional())
                        .executor(CommandSupport.player((p, ctx) -> create(p, CommandSupport.string(ctx, "name"))))
                        .build())
                .child(sub("delete", "Delete one of your lands", "Supprimer un de vos territoires")
                        .argument(support.land("land"))
                        .executor(CommandSupport.player((p, ctx) -> withLand(p, ctx, land -> delete(p, land))))
                        .build())
                .child(sub("claim", "Claim the chunk you stand in", "Protéger le tronçon où vous êtes")
                        .argument(support.land("land").optional())
                        .executor(CommandSupport.player((p, ctx) -> {
                            if (CommandSupport.string(ctx, "land") == null) {
                                claim(p, null);
                            } else {
                                withLand(p, ctx, land -> claim(p, land));
                            }
                        }))
                        .build())
                .child(sub("unclaim", "Unclaim the chunk you stand in", "Retirer la protection du tronçon où vous êtes")
                        .executor(CommandSupport.player((p, ctx) -> unclaim(p))).build())
                .child(sub("forceunclaim", "Unclaim the chunk you stand in, whoever owns it",
                        "Retirer la protection du tronçon où vous êtes, quel que soit son propriétaire")
                        .permission("lands.admin")
                        .executor(CommandSupport.player((p, ctx) -> forceunclaim(p))).build())
                .child(sub("claimat", "Claim a chunk from the map (clicked in the chat)",
                        "Protéger un tronçon depuis la carte (clic dans le chat)")
                        .argument(CommandArgument.world("world"))
                        .argument(CommandArgument.integer("x"))
                        .argument(CommandArgument.integer("z"))
                        .executor(CommandSupport.player((p, ctx) -> claimat(p, ctx.get("world", World.class),
                                CommandSupport.integer(ctx, "x", 0), CommandSupport.integer(ctx, "z", 0))))
                        .build())
                .child(sub("unclaimat", "Unclaim a chunk from the map (clicked in the chat)",
                        "Retirer la protection d'un tronçon depuis la carte (clic dans le chat)")
                        .argument(CommandArgument.world("world"))
                        .argument(CommandArgument.integer("x"))
                        .argument(CommandArgument.integer("z"))
                        .executor(CommandSupport.player((p, ctx) -> unclaimat(p, ctx.get("world", World.class),
                                CommandSupport.integer(ctx, "x", 0), CommandSupport.integer(ctx, "z", 0))))
                        .build())
                .child(sub("map", "Show the map of the lands around you", "Afficher la carte des territoires autour de vous")
                        .argument(support.land("land").optional())
                        .executor(CommandSupport.player((p, ctx) -> {
                            if (CommandSupport.string(ctx, "land") == null) {
                                map(p, null);
                            } else {
                                withLand(p, ctx, land -> map(p, land));
                            }
                        }))
                        .build())
                .child(sub("seeclaims", "Show or hide the borders of the lands", "Afficher ou masquer les bordures des territoires")
                        .executor(CommandSupport.player((p, ctx) -> seeclaims(p))).build())
                .child(sub("kick", "Kick a player out of your land", "Expulser un joueur de votre territoire")
                        .argument(CommandArgument.player("player"))
                        .executor(CommandSupport.player((p, ctx) -> kick(p, ctx.get("player", Player.class))))
                        .build())
                .child(sub("giveclaims", "Give some of your claims to a player", "Donner des claims à un joueur")
                        .argument(CommandSupport.knownPlayer("player"))
                        .argument(CommandArgument.integer("amount"))
                        .executor(CommandSupport.player((p, ctx) -> {
                            OfflinePlayer target = support.knownPlayer(p, ctx, "player");
                            if (target != null) giveClaims(p, target, CommandSupport.integer(ctx, "amount", 0));
                        }))
                        .build())
                .child(sub("bypass", "Ignore the protections", "Ignorer les protections")
                        .permission("lands.bypass")
                        .executor(CommandSupport.player((p, ctx) -> bypass(p))).build())
                .child(admin())
                .build();
    }

    private PlayerCommandDeclaration admin() {
        return sub("admin", "Land administration", "Administration des territoires")
                .permission("lands.admin")
                .child(sub("admin.setspawn", "Set where kicked players go", "Définir où vont les joueurs expulsés")
                        .permission("lands.admin")
                        .executor(CommandSupport.player((p, ctx) -> setSpawnLocation(p))).build())
                .child(sub("admin.debug", "Toggle the debug mode", "Activer ou couper le mode debug")
                        .permission("lands.admin")
                        .executor(CommandSupport.player((p, ctx) -> debugMode(p))).build())
                .child(sub("admin.chunkinfo", "Show who claimed the chunk you stand in",
                        "Afficher qui a protégé le tronçon où vous êtes")
                        .permission("lands.admin")
                        .executor(CommandSupport.player((p, ctx) -> chunkInfo(p))).build())
                .child(sub("admin.setdefaultworldclaim", "Make a land cover a whole world",
                        "Faire couvrir tout un monde par un territoire")
                        .permission("lands.admin")
                        .argument(CommandArgument.world("world"))
                        .argument(support.land("land"))
                        .executor(CommandSupport.player((p, ctx) -> withLand(p, ctx,
                                land -> setDefaultWorldClaim(p, ctx.get("world", World.class), land))))
                        .build())
                .child(sub("admin.effect", "Potion effects of a land", "Effets de potion d'un territoire")
                        .permission("lands.admin")
                        .child(sub("admin.effect.add", "Add a potion effect to a land", "Ajouter un effet de potion à un territoire")
                                .permission("lands.admin")
                                .argument(support.land("land"))
                                .argument(CommandArgument.string("effect").suggestions(ctx -> effects()))
                                .argument(CommandArgument.integer("amplifier").optional())
                                .executor(CommandSupport.player((p, ctx) -> withLand(p, ctx, land -> addEffect(p, land,
                                        CommandSupport.string(ctx, "effect"), CommandSupport.integer(ctx, "amplifier", 0)))))
                                .build())
                        .child(sub("admin.effect.remove", "Remove a potion effect from a land", "Retirer un effet de potion d'un territoire")
                                .permission("lands.admin")
                                .argument(support.land("land"))
                                .argument(CommandArgument.string("effect").suggestions(ctx -> effects()))
                                .executor(CommandSupport.player((p, ctx) -> withLand(p, ctx,
                                        land -> removeEffect(p, land, CommandSupport.string(ctx, "effect")))))
                                .build())
                        .child(sub("admin.effect.list", "List the potion effects of a land", "Lister les effets de potion d'un territoire")
                                .permission("lands.admin")
                                .argument(support.land("land"))
                                .executor(CommandSupport.player((p, ctx) -> withLand(p, ctx, land -> listEffects(p, land))))
                                .build())
                        .build())
                .child(sub("admin.command", "Commands run when entering a land", "Commandes lancées à l'entrée d'un territoire")
                        .permission("lands.admin")
                        .child(sub("admin.command.add", "Run a command when a player enters a land",
                                "Lancer une commande quand un joueur entre dans un territoire")
                                .permission("lands.admin")
                                .argument(support.land("land"))
                                .argument(CommandArgument.choice("as_console", List.of("true", "false")))
                                .argument(CommandArgument.text("command"))
                                .executor(CommandSupport.player((p, ctx) -> withLand(p, ctx, land -> addCommand(p, land,
                                        "true".equalsIgnoreCase(CommandSupport.string(ctx, "as_console")),
                                        CommandSupport.string(ctx, "command")))))
                                .build())
                        .child(sub("admin.command.remove", "Remove a command of a land (ids from the list)",
                                "Retirer une commande d'un territoire (identifiants de la liste)")
                                .permission("lands.admin")
                                .argument(CommandArgument.string("land_id"))
                                .argument(CommandArgument.string("command_id"))
                                .executor(CommandSupport.player((p, ctx) -> removeCommand(p,
                                        CommandSupport.string(ctx, "land_id"), CommandSupport.string(ctx, "command_id"))))
                                .build())
                        .child(sub("admin.command.list", "List the commands of a land", "Lister les commandes d'un territoire")
                                .permission("lands.admin")
                                .argument(support.land("land"))
                                .executor(CommandSupport.player((p, ctx) -> withLand(p, ctx, land -> listCommands(p, land))))
                                .build())
                        .build())
                .build();
    }

    /** The potion effects, as {@code /land admin effect add} takes them. */
    private static List<String> effects() {
        return Registry.EFFECT.stream()
                .map(effect -> effect.getKey().getKey())
                .sorted()
                .toList();
    }

    private static PlayerCommandDeclaration.Builder sub(String path, String en, String fr) {
        return CommandSupport.sub(ROOT, path, en, fr);
    }

    private void withLand(Player player, PlayerCommandContext ctx, java.util.function.Consumer<Land> run) {
        Land land = support.land(player, ctx, "land");
        if (land != null) run.accept(land);
    }

    public void onCommand(Player player) {
        help(player);
    }

    public void claim(Player player, @Nullable Land withLand) {
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

    public void unclaim(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        landService.unclaim(player, new SChunk(player.getLocation().getChunk()));
    }

    public void forceunclaim(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        landService.unclaim(player.getLocation().getChunk());
        messages.send(player, "unclaim.forced");
    }

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

    public void bypass(Player player) {
        plugin.setBypassing(player.getUniqueId(), !plugin.getBypass().contains(player.getUniqueId()));
        messages.send(player, plugin.isBypassing(player) ? "bypass.on" : "bypass.off");
    }

    public void create(Player player, @Nullable String name) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        if (name == null) {
            messages.send(player, "create.usage");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastCreate.get(player.getUniqueId());
        if (last != null && now - last < CREATE_COOLDOWN_MILLIS) {
            messages.send(player, "create.cooldown", "seconds", (CREATE_COOLDOWN_MILLIS - (now - last) + 999) / 1000);
            return;
        }
        lastCreate.put(player.getUniqueId(), now);
        landService.createLand(player, name, LandType.PLAYER, player.getUniqueId());
    }

    public void delete(Player player, Land land) {
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

    public void map(Player player, @Nullable Land land) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        plugin.getLandMap().display(player, land);
    }

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

    public void menu(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {

            messages.send(player, "disabled");
            return;
        }

        plugin.getMenus().openLands(player, fr.iban.lands.menu.LandMenus.Scope.PLAYER, player.getUniqueId());
    }

    public void setSpawnLocation(Player player) {
        plugin.getConfig().set("spawn-location", player.getLocation());
        plugin.saveConfig();
        messages.send(player, "admin.setspawn");
    }

    public void debugMode(Player player) {
        plugin.setDebugging(player.getUniqueId(), !plugin.isInDebugMode(player.getUniqueId()));
        messages.send(player, plugin.isInDebugMode(player) ? "admin.debug.on" : "admin.debug.off");
    }

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

    public void setDefaultWorldClaim(Player player, World world, Land land) {
        landRepository.setDefaultWorldLand(world, land);
        messages.send(player, "admin.default_world_land", "world", world.getName(), "land", land.getName());
    }

    public void help(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        messages.send(player, "help.text");
    }

    public void giveClaims(Player player, OfflinePlayer target, int amount) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            messages.send(player, "disabled");
            return;
        }

        landService.giveClaims(player, target.getUniqueId(), amount);
    }

    //land admin effect add/remove effecttype aplifier=1
    public void addEffect(Player player, Land land, String effectType, int amplifier) {
        NamespacedKey potionKey = NamespacedKey.fromString(effectType.toLowerCase(Locale.ROOT));

        if(potionKey == null || Registry.EFFECT.get(potionKey) == null) {
            messages.send(player, "admin.effect.unknown");
            return;
        }

        PotionEffectType potionEffectType = Registry.EFFECT.get(potionKey);
        landRepository.addEffect(land, potionEffectType, amplifier);
        messages.send(player, "admin.effect.added");
    }

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

    public void listEffects(Player player, Land land) {
        messages.send(player, "admin.effect.list_header", "land", land.getName());
        land.getEffects().forEach(potionEffect -> messages.send(player, "admin.effect.list_line",
                "effect", potionEffect.getType().getKey(), "amplifier", potionEffect.getAmplifier()));
    }

    public void addCommand(Player player, Land land, boolean asConsole, String command) {
        landRepository.addCommand(land, new LandEnterCommand(command, asConsole));
        messages.send(player, "admin.command.added");
    }

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

    public void listCommands(Player player, Land land) {
        messages.send(player, "admin.command.list_header", "land", land.getName());
        String console = messages.text(player, "admin.command.console", "[Console]");
        land.getEnterCommands().forEach(command -> messages.send(player, "admin.command.list_line",
                "command", command.getCommand(), "console", command.isAsConsole() ? console : "",
                "land_id", land.getId(), "command_id", command.getUniqueId()));
    }
}
