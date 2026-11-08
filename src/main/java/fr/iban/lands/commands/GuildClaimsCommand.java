package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.messages.LandMessages;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
    private final LandMessages messages;

    public GuildClaimsCommand(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
        this.messages = plugin.getMessages();
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
            messages.send(player, "guildclaims.not_in_guild");
            return;
        }

        if (!guildDataAccess.canManageGuildLand(player.getUniqueId())) {
            messages.send(player, "guildclaims.no_permission");
            return;
        }

        int count = landRepository.getChunkCount(guildId);
        int maxCount = landRepository.getMaxChunkCount(guildId);
        FileConfiguration config = plugin.getConfig();

        ConfigurationSection pricesSection = config.getConfigurationSection("guild-claim-prices");

        if (pricesSection == null) {
            messages.send(player, "guildclaims.prices_missing");
            return;
        }

        messages.send(player, "guildclaims.info.header");

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

                rangeDisplay = messages.text(player, "guildclaims.info.range_between", minClaims + " - " + maxClaims,
                        "min", minClaims, "max", maxClaims);
            } else {
                rangeDisplay = messages.text(player, "guildclaims.info.range_from", minClaims + "+", "min", minClaims);
            }

            messages.send(player, "guildclaims.info.line", "range", plain(rangeDisplay), "price", price);
        }

        messages.send(player, "guildclaims.info.footer", "count", count, "max", maxCount, "remaining", maxCount - count);
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
            messages.send(player, "guildclaims.invalid_amount");
            return;
        }

        UUID guildId = guildDataAccess.getGuildId(player.getUniqueId());

        if(guildId == null || !guildDataAccess.canManageGuildLand(player.getUniqueId())) {
            messages.send(player, "guildclaims.not_allowed");
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
                messages.send(player, "guildclaims.prices_missing");
                return;
            }

            totalPurchaseCost += unitPrice;
        }

        // Créer et afficher le dialogue de confirmation
        showPurchaseConfirmationDialog(player, guildId, amount, totalPurchaseCost, currentMaxCount, newMaxCount);
    }

    private void showPurchaseConfirmationDialog(Player player, UUID guildId, int amount, long totalCost, int currentMax, int newMax) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(messages.component(player, "guildclaims.dialog.title"))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.plainMessage(Component.empty()),
                                DialogBody.plainMessage(messages.component(player, "guildclaims.dialog.amount", "amount", amount)),
                                DialogBody.plainMessage(Component.empty()),
                                DialogBody.plainMessage(messages.component(player, "guildclaims.dialog.cost", "cost", totalCost)),
                                DialogBody.plainMessage(Component.empty()),
                                DialogBody.plainMessage(messages.component(player, "guildclaims.dialog.current", "current", currentMax)),
                                DialogBody.plainMessage(messages.component(player, "guildclaims.dialog.new", "new", newMax)),
                                DialogBody.plainMessage(Component.empty()),
                                DialogBody.plainMessage(messages.component(player, "guildclaims.dialog.question"))
                        ))
                        .build()
                )
                .type(DialogType.confirmation(
                        ActionButton.builder(messages.component(player, "guildclaims.dialog.confirm"))
                                .tooltip(messages.component(player, "guildclaims.dialog.confirm_tooltip", "amount", amount, "cost", totalCost))
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
                        ActionButton.builder(messages.component(player, "guildclaims.dialog.cancel"))
                                .tooltip(messages.component(player, "guildclaims.dialog.cancel_tooltip"))
                                .action(null)
                                .build()
                ))
        );

        player.showDialog(dialog);
    }

    private void processPurchase(Player player, UUID guildId, int amount, long totalCost, int newMaxCount) {
        AbstractGuildDataAccess guildDataAccess = plugin.getGuildDataAccess();

        String reason = messages.text(null, "guildclaims.bank_reason", "Achat de claims");
        if (totalCost > 0 && !guildDataAccess.withdraw(guildId, totalCost, reason)) {
            messages.send(player, "guildclaims.not_enough_money", "cost", totalCost);
            return;
        }

        landRepository.setChunkLimit(guildId, newMaxCount);
        messages.send(player, "guildclaims.bought", "amount", amount, "cost", totalCost, "max", newMaxCount);
    }

    /** A range as the price line shows it: the lang file's text without its tags. */
    private static String plain(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }
}
