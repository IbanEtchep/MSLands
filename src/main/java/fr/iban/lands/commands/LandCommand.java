package fr.iban.lands.commands;

import fr.iban.bukkitcore.menu.ConfirmMenu;
import fr.iban.bukkitcore.utils.HexColor;
import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.LandEnterCommand;
import fr.iban.lands.model.land.PlayerLand;
import fr.iban.lands.model.land.SystemLand;
import fr.iban.lands.utils.DateUtils;
import fr.iban.lands.utils.LandMap;
import fr.iban.lands.utils.SeeClaims;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

    public LandCommand(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
        this.landService = plugin.getLandService();
        this.playerLandsEnabled = plugin.getConfig().getBoolean("players-lands-enabled");
    }

    @CommandPlaceholder
    public void onCommand(Player player) {
        help(player);
    }

    @Subcommand("claim")
    public void claim(Player player, @Optional Land withLand) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        if (withLand == null) {
            Land firstLand = landRepository.getLands(player.getUniqueId(), LandType.PLAYER)
                    .stream().findFirst().orElse(null);

            if (firstLand == null) {
                player.sendMessage("§cVous n'avez pas de territoire. Créez en un avec /land create <NomDuTerritoire>");
                return;
            }

            withLand = firstLand;
        }

        landService.claim(player, new SChunk(player.getLocation().getChunk()), withLand);
    }

    @Subcommand("unclaim")
    public void unclaim(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        landService.unclaim(player, new SChunk(player.getLocation().getChunk()));
    }

    @Subcommand("forceunclaim")
    @CommandPermission("lands.admin")
    @SecretCommand
    public void forceunclaim(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        landService.unclaim(player.getLocation().getChunk());
        player.sendMessage("§aLe claim a été retiré.");
    }

    @Subcommand("kick")
    public void kick(Player player, Player target) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        if (!target.getUniqueId().equals(player.getUniqueId())) {
            Land land = landRepository.getLandAt(target.getLocation());
            if (landRepository.canManageLand(player, land)) {
                target.teleportAsync(
                        plugin.getConfig().getLocation("spawn-location", Objects.requireNonNull(Bukkit.getWorld("world")).getSpawnLocation()));
                target.sendMessage("§cVous avez été expulsé du territoire de " + player.getName());
                player.sendActionBar(Component.text("§aLe joueur a bien été expulsé."));
            } else {
                player.sendMessage(
                        "§cVous n'avez pas la permission d'exclure ce joueur du territoire où il se trouve.");
            }
        } else {
            player.sendMessage("§cImpossible de faire cela sur vous même...");
        }
    }

    @Subcommand("bypass")
    @CommandPermission("lands.bypass")
    public void bypass(Player player) {
        plugin.setBypassing(player.getUniqueId(), !plugin.getBypass().contains(player.getUniqueId()));
        player.sendMessage("§8§lBypass : " + (plugin.isBypassing(player) ? "§aActivé" : "§cDésactivé"));
    }

    @Subcommand("create")
    @Cooldown(value = 10)
    public void create(Player player, @Optional String name) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        if (name == null) {
            player.sendMessage("/land create <NomDeLaRegion>");
        } else {
            landService.createLand(player, name, LandType.PLAYER, player.getUniqueId());
        }
    }

    @Subcommand("delete")
    public void create(Player player, Land land) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {

            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        if (land == null) {
            player.sendMessage("§cCe territoire n'existe pas.");
        } else {
            new ConfirmMenu(player, "Supprimer le territoire " + land.getName() + " ?", confirmed -> {
                if (confirmed) {
                    landRepository.deleteLand(land);
                }
            }).open();
        }
    }

    @Subcommand("claimat")
    @SecretCommand
    public void claimat(Player player, World world, int X, int Z) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {

            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
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
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        LandMap map = plugin.getLandMap();
        UUID uuid = player.getUniqueId();

        if (!map.getLandMapSelection().isEmpty() && map.getLandMapSelection().containsKey(uuid)) {
            Land land = map.getLandMapSelection().get(uuid);

            if (land != null) {
                SChunk sChunk = new SChunk(LandsPlugin.getInstance().getServerName(), world.getName(), X, Z);

                if (land instanceof PlayerLand) {
                    landService.unclaim(player, sChunk);
                } else if (land instanceof SystemLand && player.hasPermission("lands.admin")) {
                    landService.unclaim(world.getChunkAt(X, Z));
                }

                landService.unclaim(player, sChunk);
                map.display(player, land);
                player.sendActionBar("§a§lLe tronçon a bien été unclaim.");
            }
        }
    }

    @Subcommand("map")
    public void map(Player player, @Optional Land land) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        plugin.getLandMap().display(player, land);
    }

    @Subcommand("seeclaims")
    public void seeclaims(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!plugin.getSeeClaims().containsKey(uuid)) {
            SeeClaims sc = new SeeClaims(player, plugin);
            plugin.getSeeClaims().put(player.getUniqueId(), sc);
            sc.showWalls();
            player.sendMessage("§aMode vision de claims activé.");
        } else {
            SeeClaims sc = plugin.getSeeClaims().get(uuid);
            sc.stop();
            plugin.getSeeClaims().remove(uuid);
            player.sendMessage("§cMode vision de claims désactivé.");
        }
    }

    @Subcommand("menu")
    public void menu(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {

            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        player.performCommand("lands");
    }

    @Subcommand("admin setspawn")
    @CommandPermission("lands.admin")
    public void setSpawnLocation(Player player) {
        plugin.getConfig().set("spawn-location", player.getLocation());
        plugin.saveConfig();
        player.sendMessage("§aPosition du spawn redéfini.");
    }

    @Subcommand("admin debug")
    @CommandPermission("lands.admin")
    public void debugMode(Player player) {
        plugin.setDebugging(player.getUniqueId(), !plugin.isInDebugMode(player.getUniqueId()));
        player.sendMessage(
                "§fMode debug : " + (plugin.isInDebugMode(player) ? "§aActivé" : "§cDésactivé"));
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
            player.sendMessage("§6§lChunk X:" + schunk.getX() + " Z:" + schunk.getZ());
            player.sendMessage("§8Type : §f" + land.getType().toString());
            player.sendMessage("§8Claim le : §f" + DateUtils.format(schunk.getCreatedAt()));
        } else {
            player.sendMessage("§cCe chunk n'est pas claim.");
        }
    }

    @Subcommand("admin setDefaultWorldClaim")
    @CommandPermission("lands.admin")
    public void setDefaultWorldClaim(Player player, World world, Land land) {
        landRepository.setDefaultWorldLand(world, land);
        player.sendMessage(String.format("§cLe claim par défaut du monde %s est désormais %s.", world.getName(), land.getName()));
    }

    @Subcommand("help")
    public void help(Player player) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
            return;
        }

        player.sendMessage(HexColor.MARRON_CLAIR.getColor() + "La protection de vos territoires se gère avec les commandes ci-dessous.");
        player.sendMessage("");
        player.sendMessage(getCommandUsage("/lands", "Ouvre le menu de gestion de vos territoires."));
        player.sendMessage(
                getCommandUsage(
                        "/land claim <territoire(optionnel)>",
                        "Attribue le tronçon où vous vous trouvez au territoire choisi ou à votre premier territoire."));
        player.sendMessage(
                getCommandUsage(
                        "/land unclaim", "Retire le tronçon où vous vous trouvez de vos territoires."));
        player.sendMessage(
                getCommandUsage(
                        "/land map <territoire(optionnel)>",
                        "Affiche une carte des territoires alentours. Permet de claim/unclaim si un territoire est selectionné."));
        player.sendMessage(
                getCommandUsage(
                        "/land kick <joueur> ",
                        "Renvois un joueur qui se trouve dans votre territoire au spawn."));
        player.sendMessage(
                getCommandUsage("/land seeclaims ", "Permet de voir les bordures des claims."));
        player.sendMessage("");
        player.sendMessage(
                HexColor.MARRON_CLAIR.getColor()
                        + "Les "
                        + HexColor.MARRON.getColor()
                        + "tronçons(chunks) "
                        + HexColor.MARRON_CLAIR.getColor()
                        + "mesurent "
                        + HexColor.MARRON.getColor()
                        + "16x256x16 blocs"
                        + HexColor.MARRON_CLAIR.getColor()
                        + " et sont visible en appuyant sur les touche "
                        + HexColor.MARRON.getColor()
                        + "F3+G"
                        + HexColor.MARRON_CLAIR.getColor()
                        + ".");
    }

    private Component getCommandUsage(String command, String desc) {
        Component baseComponent = Component.text("- ", TextColor.fromHexString(HexColor.MARRON_CLAIR.getHex()));

        Component commandComponent = Component.text(command)
                .color(TextColor.fromHexString(HexColor.MARRON.getHex()))
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Clic pour écrire la commande").color(NamedTextColor.GRAY)));

        baseComponent = baseComponent.append(commandComponent);
        baseComponent = baseComponent.append(Component.text(" - ", TextColor.fromHexString(HexColor.MARRON_CLAIR.getHex())));
        baseComponent = baseComponent.append(Component.text(desc).color(TextColor.fromHexString(HexColor.MARRON_CLAIR.getHex())));

        return baseComponent;
    }

    @Subcommand("giveclaims")
    private void giveClaims(Player player, OfflinePlayer target, int amount) {
        if (!playerLandsEnabled && !player.hasPermission("lands.bypass")) {
            player.sendMessage("§cLes territoires ne sont pas activés sur ce serveur.");
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
            player.sendMessage("§cCet effet n'existe pas.");
            return;
        }

        PotionEffectType potionEffectType = Registry.EFFECT.get(potionKey);
        landRepository.addEffect(land, potionEffectType, amplifier);
        player.sendMessage("§aEffet ajouté.");
    }

    @Subcommand("admin effect remove")
    @CommandPermission("lands.admin")
    public void removeEffect(Player player, Land land, String effectType) {
        NamespacedKey potionKey = NamespacedKey.fromString(effectType.toLowerCase(Locale.ROOT));

        if(potionKey == null || Registry.EFFECT.get(potionKey) == null) {
            player.sendMessage("§cCet effet n'existe pas.");
            return;
        }

        PotionEffectType potionEffectType = Registry.EFFECT.get(potionKey);
        landRepository.removeEffect(land, potionEffectType);
        player.sendMessage("§aEffet retiré.");
    }

    @Subcommand("admin effect list")
    @CommandPermission("lands.admin")
    public void listEffects(Player player, Land land) {
        player.sendMessage("§6Effets actifs sur le territoire " + land.getName() + " :");
        land.getEffects().forEach((potionEffect) -> {
            player.sendMessage("§8- §f" + potionEffect.getType().getKey() + " " + potionEffect.getAmplifier());
        });
    }

    @Subcommand("admin command add")
    @CommandPermission("lands.admin")
    @Usage("/land admin command add <land> <asConsole> <command>")
    public void addCommand(Player player, Land land, boolean asConsole, String command) {
        landRepository.addCommand(land, new LandEnterCommand(command, asConsole));
        player.sendMessage("§aCommande ajoutée.");
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
            player.sendMessage("§cL'ID du territoire ou de la commande est invalide.");
            return;
        }

        Land land = landRepository.getLandById(landId).orElse(null);

        if(land == null) {
            player.sendMessage("§cCe territoire n'existe pas.");
            return;
        }

        LandEnterCommand command = land.getEnterCommands().stream()
                .filter(c -> c.getUniqueId().equals(commandId))
                .findFirst().orElse(null);

        if(command == null) {
            player.sendMessage("§cCette commande n'existe pas.");
            return;
        }

        landRepository.removeCommand(land, command);

        player.sendMessage("§aCommande retirée.");
    }

    @Subcommand("admin command list")
    @CommandPermission("lands.admin")
    @Usage("/land admin command list <land>")
    public void listCommands(Player player, Land land) {
        player.sendMessage("§6Commandes d'entrée du territoire " + land.getName() + " :");

        land.getEnterCommands().forEach(landEnterCommand -> {
            Component commandComponent = Component.text("§8- §f" + landEnterCommand.getCommand() + " ")
                    .append(Component.text(landEnterCommand.isAsConsole() ? "§8[Console]" : ""))
                    .append(Component.space())
                    .append(
                            Component.text("[Supprimer]")
                                    .color(NamedTextColor.RED)
                                    .decorate(TextDecoration.BOLD)
                                    .hoverEvent(Component.text("Clique pour supprimer cette commande").color(NamedTextColor.GRAY))
                                    .clickEvent(ClickEvent.runCommand(
                                            "/land admin command remove " + land.getId() + " " + landEnterCommand.getUniqueId()
                                    ))
                    );

            player.sendMessage(commandComponent);
        });
    }
}
