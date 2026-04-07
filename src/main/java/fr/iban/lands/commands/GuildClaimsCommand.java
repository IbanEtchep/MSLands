package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.guild.AbstractGuildDataAccess;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Subcommand;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Command({"guildclaims"})
public class GuildClaimsCommand {

    private final LandRepository landRepository;
    private final LandsPlugin plugin;

    public GuildClaimsCommand(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
    }

    @CommandPlaceholder
    public void onCommand(Player player) {
        info(player);
    }

    @Subcommand("info")
    public void info(Player player) {
        AbstractGuildDataAccess guildDataAccess = plugin.getGuildDataAccess();
        UUID guildId = guildDataAccess.getGuildId(player.getUniqueId());

        if(guildId == null) {
            player.sendMessage("§cVous n'êtes pas dans une guilde");
            return;
        }

        if (!guildDataAccess.canManageGuildLand(player.getUniqueId())) {
            player.sendMessage("§cVous n'avez pas la permission d'accéder à cette commande");
            return;
        }

        int count = landRepository.getChunkCount(guildId);
        int maxCount = landRepository.getMaxChunkCount(guildId);
        FileConfiguration config = plugin.getConfig();

        ConfigurationSection pricesSection = config.getConfigurationSection("guild-claim-prices");

        if (pricesSection == null) {
            player.sendMessage("§cErreur : La section des prix des claims n'est pas trouvée dans la configuration.");
            return;
        }

        player.sendMessage("§6--- Barème d'Achat des Claims de Guilde ---");
        player.sendMessage("§7Nombre de Claims | Coût d'Achat");
        player.sendMessage("§7---------------------------------");

        Set<String> keys = pricesSection.getKeys(false);

        List<Integer> sortedKeys = keys.stream()
                .map(Integer::parseInt)
                .sorted()
                .toList();

        for (int i = 0; i < sortedKeys.size(); i++) {
            int minClaims = sortedKeys.get(i);
            int price = pricesSection.getInt(String.valueOf(minClaims));

            String rangeDisplay;

            if (i < sortedKeys.size() - 1) {
                int nextMinClaims = sortedKeys.get(i + 1);
                int maxClaims = nextMinClaims - 1;

                rangeDisplay = minClaims + " à " + maxClaims;
            } else {
                rangeDisplay = minClaims + " et +";
            }

            player.sendMessage(String.format("§a%s §7-> §e%d",
                    rangeDisplay,
                    price));
        }

        player.sendMessage("§e---------------------------------");
        player.sendMessage("§6▶ Informations de votre Guilde ◀");
        player.sendMessage(String.format("§bClaims Actuels : §f%d §7/ §f%d", count, maxCount));
        int remainingClaims = maxCount - count;
        player.sendMessage(String.format("§bClaims Restants : §a%d", remainingClaims));
        player.sendMessage("§e---------------------------------");
        player.sendMessage("§6---------------------------------");
        player.sendMessage("§7Pour acheter des claims, utilisez :");
        player.sendMessage("§a/guildclaims buy <nombre>");
        player.sendMessage("§6---------------------------------");
    }

    public int getUnitClaimPriceSimple(int claimIndex, FileConfiguration config) {
        ConfigurationSection pricesSection = config.getConfigurationSection("guild-claim-prices");
        if (pricesSection == null) return -1; // Indicateur d'erreur

        List<Integer> sortedKeys = pricesSection.getKeys(false).stream()
                .map(Integer::parseInt)
                .sorted()
                .toList();

        int unitPrice = 0;

        // On cherche la tranche où claimIndex est inclus (minClaims <= claimIndex)
        for (int minClaims : sortedKeys) {
            if (claimIndex >= minClaims) {
                unitPrice = pricesSection.getInt(String.valueOf(minClaims));
            } else {
                // Le prix a été trouvé dans la tranche précédente.
                return unitPrice;
            }
        }
        // Si la boucle se termine, claimIndex est dans la dernière tranche (201 et +).
        return unitPrice;
    }


    @Subcommand("buy")
    public void buy(Player player, @Single int amount) {
        AbstractGuildDataAccess guildDataAccess = plugin.getGuildDataAccess();

        if (amount <= 0) {
            player.sendMessage("§cVeuillez spécifier un nombre de claims valide à acheter.");
            return;
        }

        UUID guildId = guildDataAccess.getGuildId(player.getUniqueId());

        if(guildId == null || !guildDataAccess.canManageGuildLand(player.getUniqueId())) {
            player.sendMessage("§cVous n'êtes pas dans une guilde ou vous n'avez pas la permission de gérer les claims.");
            return;
        }

        int currentMaxCount = landRepository.getMaxChunkCount(guildId);
        int newMaxCount = currentMaxCount + amount;

        FileConfiguration config = plugin.getConfig();

        long totalPurchaseCost = 0;

        for (int i = 1; i <= amount; i++) {
            int claimIndexToPrice = currentMaxCount + i;
            int unitPrice = getUnitClaimPriceSimple(claimIndexToPrice, config);

            if (unitPrice == -1) {
                player.sendMessage("§cErreur de configuration du barème de prix.");
                return;
            }

            totalPurchaseCost += unitPrice;
        }

        // Créer et afficher le dialogue de confirmation
        showPurchaseConfirmationDialog(player, guildId, amount, totalPurchaseCost, currentMaxCount, newMaxCount);
    }

    private void showPurchaseConfirmationDialog(Player player, UUID guildId, int amount, long totalCost, int currentMax, int newMax) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Confirmer l'achat de claims", NamedTextColor.GOLD))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.plainMessage(Component.empty()),
                                DialogBody.plainMessage(Component.text("Nombre de claims à acheter: ", NamedTextColor.GRAY)
                                        .append(Component.text(amount, NamedTextColor.WHITE))),
                                DialogBody.plainMessage(Component.empty()),
                                DialogBody.plainMessage(Component.text("Coût total: ", NamedTextColor.GRAY)
                                        .append(Component.text(totalCost + " $", NamedTextColor.YELLOW))),
                                DialogBody.plainMessage(Component.empty()),
                                DialogBody.plainMessage(Component.text("Claims actuels: ", NamedTextColor.GRAY)
                                        .append(Component.text(currentMax, NamedTextColor.WHITE))),
                                DialogBody.plainMessage(Component.text("Nouveaux claims: ", NamedTextColor.GRAY)
                                        .append(Component.text(newMax, NamedTextColor.GREEN))),
                                DialogBody.plainMessage(Component.empty()),
                                DialogBody.plainMessage(Component.text("Voulez-vous confirmer cet achat?", NamedTextColor.AQUA))
                        ))
                        .build()
                )
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("✓ Confirmer", TextColor.color(0xAEFFC1)))
                                .tooltip(Component.text("Acheter " + amount + " claims pour " + totalCost + " $"))
                                .action(DialogAction.customClick(
                                        (view, audience) -> {
                                            if (audience instanceof Player p) {
                                                processPurchase(p, guildId, amount, totalCost, newMax);
                                            }
                                        },
                                        ClickCallback.Options.builder()
                                                .uses(1)
                                                .lifetime(ClickCallback.DEFAULT_LIFETIME)
                                                .build()
                                ))
                                .build(),
                        ActionButton.builder(Component.text("✗ Annuler", TextColor.color(0xFFA0B1)))
                                .tooltip(Component.text("Annuler l'achat"))
                                .action(null)
                                .build()
                ))
        );

        player.showDialog(dialog);
    }

    private void processPurchase(Player player, UUID guildId, int amount, long totalCost, int newMaxCount) {
        AbstractGuildDataAccess guildDataAccess = plugin.getGuildDataAccess();

        if (totalCost > 0 && !guildDataAccess.withdraw(guildId, totalCost, "Achat de claims")) {
            player.sendMessage(Component.text("Votre guilde n'a pas assez d'argent, il vous faut " + totalCost + "$", NamedTextColor.RED));
            return;
        }

        landRepository.setChunkLimit(guildId, newMaxCount);

        player.sendMessage(Component.text("Achat de claims réussi !", NamedTextColor.GREEN));
        player.sendMessage(Component.text("Votre guilde a acheté ", NamedTextColor.GREEN)
                .append(Component.text(amount, NamedTextColor.WHITE))
                .append(Component.text(" claims maximum pour un coût total de ", NamedTextColor.GREEN))
                .append(Component.text(totalCost, NamedTextColor.YELLOW))
                .append(Component.text(" $.", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("Nouveau maximum de claims: ", NamedTextColor.GREEN)
                .append(Component.text(newMaxCount, NamedTextColor.WHITE)));
    }
}
