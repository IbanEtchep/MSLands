package fr.iban.lands.menu;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.model.land.Land;
import net.artisanmc.modules.api.ArtisanAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Opens the land menus, Artisan menus of the module {@code lands} ({@link LandsModule}), from the
 * commands and from the selectors. Every argument is passed, empty when unused, so no menu reads a
 * token left as written.
 */
public final class LandMenus {

    /** Whose lands {@code lands:main} lists. */
    public enum Scope {
        PLAYER, GUILD, SYSTEM, SUBLANDS;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable Scope parse(@Nullable String key) {
            if (key == null) return null;
            for (Scope scope : values()) {
                if (scope.key().equalsIgnoreCase(key.trim())) return scope;
            }
            return null;
        }
    }

    public static final String MAIN = LandsModule.FOLDER + ":main";
    public static final String MANAGE = LandsModule.FOLDER + ":manage";
    public static final String CLAIMS = LandsModule.FOLDER + ":claims";
    public static final String TRUST_EDIT = LandsModule.FOLDER + ":trust_edit";
    public static final String DELETE_CONFIRM = LandsModule.FOLDER + ":delete_confirm";

    private final ArtisanAPI api;
    private final LandsPlugin plugin;
    private final LandMenuData data;

    public LandMenus(ArtisanAPI api, LandsPlugin plugin) {
        this.api = api;
        this.plugin = plugin;
        this.data = new LandMenuData(plugin);
    }

    LandMenuData data() {
        return data;
    }

    /** The lands of {@code owner} (a player or a guild; null for system lands), after reading their chunk counts. */
    public void openLands(Player player, Scope scope, @Nullable UUID owner) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", scope.key());
        args.put("owner", owner == null ? "" : owner.toString());
        args.put("parent", "");
        data.refreshLimits(scope == Scope.PLAYER || scope == Scope.GUILD ? owner : null,
                () -> open(player, MAIN, args));
    }

    /** The sub-lands of {@code parent}. */
    public void openSubLands(Player player, Land parent) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", Scope.SUBLANDS.key());
        args.put("owner", "");
        args.put("parent", parent.getId().toString());
        open(player, MAIN, args);
    }

    public void openManage(Player player, Land land) {
        open(player, MANAGE, Map.of("land", land.getId().toString()));
    }

    public void openClaims(Player player, Land land) {
        open(player, CLAIMS, Map.of("land", land.getId().toString()));
    }

    /** {@code target}: {@code global}, {@code guild} or a player's UUID. */
    public void openTrustEdit(Player player, Land land, String target) {
        open(player, TRUST_EDIT, Map.of("land", land.getId().toString(), "target", target, "group", ""));
    }

    /** From {@code /land delete}: confirming closes the menu (from the main menu, it goes back to the list). */
    public void openDeleteConfirm(Player player, Land land) {
        open(player, DELETE_CONFIRM, Map.of("land", land.getId().toString(), "from", "command"));
    }

    private void open(Player player, String ref, Map<String, Object> args) {
        if (!player.isOnline()) return;
        if (!api.getMenus().open(player, ref, args)) {
            plugin.getMessages().send(player, "menu.unavailable");
            plugin.getLogger().warning("Menu " + ref + " not found: is plugins/Artisan/modules/" + LandsModule.FOLDER
                    + " loaded? (/artisan reload)");
        }
    }
}
