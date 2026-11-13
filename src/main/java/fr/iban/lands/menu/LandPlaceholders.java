package fr.iban.lands.menu;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.guild.AbstractGuildDataAccess;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.model.land.Land;
import net.artisanmc.modules.api.placeholder.PlaceholderResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code %lands_<key>%}, in Artisan's menus and, through PlaceholderAPI, in scoreboards and chat.
 * Everything is read from memory: the chunk counts come from the menus' cache, read in the
 * background ({@code …} until the first read).
 *
 * <ul>
 *   <li>{@code here}: the land where the player stands (sub-land included), {@code here_owner} its
 *       owner (a player, a guild, empty for a system land), {@code here_type} {@code player},
 *       {@code guild}, {@code system}, {@code subland} or {@code wilderness};</li>
 *   <li>{@code lands}: how many lands the player owns;</li>
 *   <li>{@code claims}, {@code claims_max}, {@code claims_remaining}: the player's chunks used and
 *       allowed, on every server.</li>
 * </ul>
 */
public final class LandPlaceholders implements PlaceholderResolver {

    public static final String NAMESPACE = "lands";
    public static final List<String> KEYS = List.of("here", "here_owner", "here_type", "lands", "claims",
            "claims_max", "claims_remaining");
    private static final String PENDING = "…";

    private final LandsPlugin plugin;
    private final LandRepository lands;
    private final LandMessages messages;
    private final LandMenuData data;

    public LandPlaceholders(LandsPlugin plugin, LandMenuData data) {
        this.plugin = plugin;
        this.lands = plugin.getLandRepository();
        this.messages = plugin.getMessages();
        this.data = data;
    }

    /** Null (the token stays as written) for an unknown key, and for an offline player (PlaceholderAPI). */
    @Override
    public @Nullable String resolve(@Nullable Player player, String key) {
        if (player == null) return null;
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "here" -> messages.landName(player, here(player));
            case "here_owner" -> owner(here(player));
            case "here_type" -> {
                Land land = here(player);
                yield lands.isWilderness(land) ? "wilderness" : land.getType().name().toLowerCase(Locale.ROOT);
            }
            case "lands" -> String.valueOf(lands.getLands(player.getUniqueId(), LandType.PLAYER).size());
            case "claims" -> limit(player, Kind.USED);
            case "claims_max" -> limit(player, Kind.MAX);
            case "claims_remaining" -> limit(player, Kind.REMAINING);
            default -> null;
        };
    }

    private Land here(Player player) {
        return lands.getLandAt(player.getLocation());
    }

    private String owner(Land land) {
        UUID owner = land.getOwner();
        if (owner == null) return "";
        if (land.getType() == LandType.GUILD) {
            AbstractGuildDataAccess guilds = plugin.getGuildDataAccess();
            String name = guilds != null ? guilds.getGuildName(owner) : null;
            return name != null ? name : "";
        }
        String name = Bukkit.getOfflinePlayer(owner).getName();
        return name != null ? name : "";
    }

    private enum Kind { USED, MAX, REMAINING }

    private String limit(Player player, Kind kind) {
        LandMenuData.Limits limits = data.cachedLimits(player.getUniqueId());
        if (limits == null) return PENDING;
        return String.valueOf(switch (kind) {
            case USED -> limits.used();
            case MAX -> limits.max();
            case REMAINING -> Math.max(0, limits.max() - limits.used());
        });
    }
}
