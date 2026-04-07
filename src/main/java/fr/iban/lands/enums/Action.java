package fr.iban.lands.enums;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;

public enum Action {
    ALL("Tout", new ItemStack(Material.NETHER_STAR), null),
    BLOCK_BREAK("Casser des blocs", new ItemStack(Material.IRON_PICKAXE), ActionGroup.BUILDING),
    BLOCK_PLACE("Placer des blocs", new ItemStack(Material.DIRT), ActionGroup.BUILDING),
    OPEN_CONTAINER("Ouvrir les conteneurs (coffre, fours, hoopers...)", new ItemStack(Material.CHEST), ActionGroup.CONTAINERS),
    BUCKET_EMPTY("Vider des sceaux", new ItemStack(Material.LAVA_BUCKET), ActionGroup.BUILDING),
    BUCKET_FILL("Remplir des sceaux", new ItemStack(Material.BUCKET), ActionGroup.BUILDING),
    USE("Utiliser les portes, trappes, répéteurs...", new ItemStack(Material.BIRCH_DOOR), ActionGroup.USE),
    PASSIVE_KILL("Taper les entités passives", new ItemStack(Material.IRON_SWORD), null),
    TAGGED_KILL("Tuer les entités renommées", new ItemStack(Material.NAME_TAG), null),
    SET_WARP("Mettre un warp dans votre claim", new ItemStack(Material.NETHER_STAR), ActionGroup.PLUGINS),
    SET_HOME("Mettre une résidence (home) dans votre claim", new ItemStack(Material.BLUE_BED), ActionGroup.PLUGINS),
    SHOP_CREATE("Créer un shop dans votre warp", new ItemStack(Material.CHEST), ActionGroup.PLUGINS),
    SHOP_OPEN("Ouvrir les shops dans votre warp", new ItemStack(Material.CHEST), ActionGroup.PLUGINS),
    USE_BED("Utiliser les lits", new ItemStack(Material.RED_BED), ActionGroup.USE),
    USE_RESPAWN_ANCHOR("Utiliser les ancres de réapparition", new ItemStack(Material.RESPAWN_ANCHOR), ActionGroup.USE),
    USE_ANVIL("Utiliser les enclumes", new ItemStack(Material.ANVIL), ActionGroup.USE),
    ENTITY_INTERACT("Interagir avec les entités (ex: villageois)", new ItemStack(Material.EMERALD), ActionGroup.INTERACT),
    LECTERN_TAKE("Prendre/poser des livres sur les pupitres", new ItemStack(Material.LECTERN), ActionGroup.USE),
    LECTERN_READ("Lire les livres dans les pupitres", new ItemStack(Material.LECTERN), ActionGroup.USE),
    BREWING_STAND_INTERACT("Utiliser les alambics", new ItemStack(Material.BREWING_STAND), ActionGroup.USE),
    PHYSICAL_INTERACT("Interactions physiques (plaques de pression, crochets...)", new ItemStack(Material.OAK_PRESSURE_PLATE), ActionGroup.INTERACT),
    FLOWER_POT_INTERACT("Interactions avec les pots de fleurs", new ItemStack(Material.FLOWER_POT), ActionGroup.INTERACT),
    CHISELED_BOOKSHELF_INTERACT("Interactions avec les bibliothèques sculptées", new ItemStack(Material.CHISELED_BOOKSHELF), ActionGroup.INTERACT),
    USE_SPAWN_EGG("Utiliser les oeufs de spawn", new ItemStack(Material.HORSE_SPAWN_EGG), ActionGroup.INTERACT),
    FERTILIZE("Fertiliser les terres", new ItemStack(Material.BONE_MEAL), ActionGroup.INTERACT),
    DRAGON_EGG_INTERACT("Interagir avec les oeufs de dragon", new ItemStack(Material.DRAGON_EGG), ActionGroup.INTERACT),
    DROP("Jeter des items", new ItemStack(Material.ROTTEN_FLESH), null),
    LEASH("Attacher un animal avec une laisse.", new ItemStack(Material.LEAD), ActionGroup.INTERACT),
    ARMOR_STAND_INTERACT("Interagir avec un porte armure.", new ItemStack(Material.ARMOR_STAND), ActionGroup.INTERACT),
    FROST_WALK("Générer de la glace avec l'enchantement semelles givrantes", new ItemStack(Material.PACKED_ICE), ActionGroup.BUILDING),
    CHORUS_TELEPORT("Se téléporter en mangeant des chorus", new ItemStack(Material.CHORUS_FRUIT), ActionGroup.TELEPORT),
    ENDER_PEARL_TELEPORT("Se téléporter avec des ender pearls", new ItemStack(Material.ENDER_PEARL), ActionGroup.TELEPORT),
    CAULDRON_FILL_EMPTY("Remplir/vider un chaudron", new ItemStack(Material.CAULDRON), ActionGroup.BUILDING),
    VEHICLE_PLACE_BREAK("Poser/casser des véhicules (bateaux, minecarts)", new ItemStack(Material.MINECART), ActionGroup.BUILDING),
    CHANGE_BEACON_EFFECT("Changer l'effet des balises", new ItemStack(Material.BEACON), ActionGroup.USE),
    SIGN_EDIT("Modifier les pancartes", new ItemStack(Material.OAK_SIGN), ActionGroup.INTERACT),
    SHELF_INTERACT("Interactions avec les étagères", new ItemStack(Material.OAK_SHELF), ActionGroup.INTERACT);

    private final String displayName;
    private final ItemStack item;
    private final ActionGroup actionGroup;

    Action(String displayName, ItemStack item, ActionGroup actionGroup) {
        this.displayName = displayName;
        this.item = item;
        this.actionGroup = actionGroup;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ItemStack getItem() {
        return item;
    }

    public static Action getByDisplayName(String displayName) {
        for (Action action : Action.values()) {
            if (displayName.contains(action.getDisplayName())) return action;
        }
        return null;
    }

    public ActionGroup getActionGroup() {
        return actionGroup;
    }

    public static List<Action> getActionsGrouped(ActionGroup actionGroup) {
        return Arrays.stream(values())
                .filter(action -> action.getActionGroup() == actionGroup)
                .toList();
    }
}
