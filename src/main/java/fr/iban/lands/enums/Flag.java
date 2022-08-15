package fr.iban.lands.enums;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public enum Flag {
	
	EXPLOSIONS("Active les explosions", new ItemStack(Material.TNT), false),
	BLOCK_DAMAGES_BY_ENTITY("Active les dégâts aux blocs par les entités (endermans, withers...)", new ItemStack(Material.DIRT), false),
	PRESSURE_PLATE_BY_ENTITY("Active l'utilisation des plaques de pression par les entités", new ItemStack(Material.OAK_PRESSURE_PLATE), false),
	TRIPWIRE_BY_ENTITY("Active l'utilisation des crochets par les entités", new ItemStack(Material.TRIPWIRE_HOOK), false),
	FARMLAND_GRIEF("Active la destruction des terres labourées", new ItemStack(Material.FARMLAND), false),
	INVINCIBLE("Active l'invincibilité pour les joueurs", new ItemStack(Material.IRON_SWORD), true),
	//MOB_DAMAGES("Active les dégats sur les entités aggressives", new ItemStack(Material.IRON_SWORD), true),
	PVP("Active le pvp", new ItemStack(Material.DIAMOND_SWORD), true),
	AUTO_REPLANT("Active l'auto replantation", new ItemStack(Material.GOLDEN_SHOVEL), true),
	DOORS_AUTOCLOSE("Active la fermeture automatique des portes", new ItemStack(Material.OAK_DOOR), true),
	NO_MOB_SPAWNING("Désactive le spawn des mobs", new ItemStack(Material.ZOMBIE_SPAWN_EGG), true),
	SILENT_MOBS("Désactive le bruit des mobs de ce territoire", new ItemStack(Material.JUKEBOX), false),
	INVISIBLE("Rend les joueurs à l'intérieur invisibles.", new ItemStack(Material.POTION), true),
	FIRE("Active les dégâts du feu et sa propagation.", new ItemStack(Material.FLINT_AND_STEEL), false),
	LIQUID_SPREAD("Autorise les liquides extérieurs au claim à se propager.", new ItemStack(Material.LAVA_BUCKET), false);

	private String displayName;
	private ItemStack item;
	private boolean system;
	
	Flag(String displayName, ItemStack item, boolean system) {
		this.displayName = displayName;
		this.item = item;
		this.system = system;
	}


	public String getDisplayName() {
		return displayName;
	}
	
	public ItemStack getItem() {
		return item;
	}
	
	public static Flag getByDisplayName(String displayName) {
		for (Flag action : values()) {
			if(displayName.contains(action.getDisplayName()))
				return action;
		}
		return null;
	}


	public boolean isSystem() {
		return system;
	}
}
