package fr.iban.lands.menus;

import fr.iban.bukkitcore.CoreBukkitPlugin;
import fr.iban.bukkitcore.menu.ConfirmMenu;
import fr.iban.bukkitcore.menu.PaginatedMenu;
import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.utils.ChatUtils;
import fr.iban.lands.utils.Head;
import fr.iban.lands.utils.ItemBuilder;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LandMainMenu extends PaginatedMenu {

    private final LandService landService;
    private final LandRepository landRepository;
    private final List<Land> lands;
    private final LandsPlugin plugin;
    private final LandType landType;

    private final UUID landOwner;

    public LandMainMenu(Player player, LandsPlugin plugin, List<Land> lands, LandType landType, UUID landOwner) {
        super(player);
        this.landService = plugin.getLandService();
        this.landRepository = plugin.getLandRepository();
        this.lands = lands;
        this.plugin = plugin;
        this.landType = landType;
        this.landOwner = landOwner;
    }

    @Override
    public String getMenuName() {
        return switch (landType) {
            case SYSTEM -> "§2§lTerritoires système:";
            case SUBLAND -> "§2§lSous-territoires :";
            case GUILD -> "§2§lTerritoires guilde :";
            default -> "§2§lVos territoires :";
        };
    }

    @Override
    public int getRows() {
        return 4;
    }

    @Override
    public int getElementAmount() {
        return lands.size();
    }

    @Override
    public void handleMenu(InventoryClickEvent e) {
        Player player = (Player) e.getWhoClicked();
        ItemStack current = e.getCurrentItem();

        if (current == null) return;

        if (e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }

        checkBottonsClick(current, player);

        if (displayNameEquals(current, "§2Créer")) {
            CoreBukkitPlugin core = CoreBukkitPlugin.getInstance();
            player.closeInventory();
            player.sendMessage("§2§lVeuillez entrer le nom du territoire souhaité :");
            core.getTextInputs().put(player.getUniqueId(), component -> {
                String text = ChatUtils.toPlainText(component);

                Land newLand = landService.createLand(player, text, landType, landOwner);

                if (newLand != null) {
                    lands.add(newLand);
                    open();
                }

                core.getTextInputs().remove(player.getUniqueId());
            });
        }

        if (current.getType() == Material.PLAYER_HEAD) {
            Land land = getLandByName(current.getItemMeta().getDisplayName());

            if (land == null) {
                return;
            }

            ClickType click = e.getClick();
            if (click == ClickType.RIGHT) {
                new ConfirmMenu(player,
                        "§cConfirmation de suppression",
                        "§eVoulez-vous supprimer le territoire " + land.getName() + " ?",
                        result -> {
                            if (result) {
                                lands.remove(land);
                                plugin.getLandRepository().deleteLand(land);
                                player.sendMessage("§cLe territoire " + land.getName() + " a bien été supprimé.");
                            }
                            super.open();
                        }
                ).open();
            } else if (click == ClickType.LEFT) {
                new LandManageMenu(player, plugin, land, this).open();
            }
        }
    }

    @Override
    public void setMenuItems() {
        addMenuBorder();

        if (lands != null && !lands.isEmpty()) {
            for (int i = 0; i < getMaxItemsPerPage(); i++) {
                index = getMaxItemsPerPage() * page + i;
                if (index >= lands.size()) break;
                if (lands.get(index) != null) {
                    Land land = lands.get(index);
                    inventory.addItem(getLandItem(land));
                }
            }
        }

        if (landOwner != null) {
            inventory.setItem(27,
                    new ItemBuilder(Head.OAK_INFO.get())
                            .setName("§2Informations")
                            .addLore("§cChargement...")
                            .build());
            getInfoItem().thenAccept(item -> inventory.setItem(27, item));
        }

        inventory.setItem(
                35,
                new ItemBuilder(Head.OAK_PLUS.get())
                        .setName("§2Créer")
                        .addLore("§aPermet de créer un nouveau territoire")
                        .build());
    }

    private ItemStack getLandItem(Land land) {
        ItemBuilder itemBuilder =
                new ItemBuilder(Head.GRASS.get())
                        .setDisplayName("§2" + land.getName())
                        .addLore("§aClic gauche pour gérer ce territoire.")
                        .addLore("§cClic droit pour supprimer ce territoire.")
                        .addLore("")
                        .addLore("§fTronçons : " + plugin.getLandRepository().getChunks(land).size())
                        .addLore("§fJoueurs trust : " + land.getTrusts().size())
                        .addLore("§fBannissements : " + land.getBans().size());
        return itemBuilder.build();
    }

    private Land getLandByName(String string) {
        for (Land land : lands) {
            if (ChatColor.stripColor(string).equals(land.getName())) {
                return land;
            }
        }
        return null;
    }

    private CompletableFuture<ItemStack> getInfoItem() {
        return CompletableFuture.supplyAsync(() -> {
            int count = landRepository.getChunkCount(landOwner);
            int maxCount = landRepository.getMaxChunkCount(landOwner);

            ItemBuilder itemBuilder = new ItemBuilder(Head.OAK_INFO.get())
                    .setName("§2Informations")
                    .addLore("§f§lTronçons utilisés : §a" + count + "/" + maxCount);

            if (landType == LandType.GUILD) {
                itemBuilder.addLore("§7Pour acheter des claims de guilde, ");
                itemBuilder.addLore("§7utilisez la commande /guildclaims");
            }

            return itemBuilder.build();
        });
    }
}
