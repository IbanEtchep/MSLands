package fr.iban.lands.messages;

import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.ActionGroup;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.model.land.Land;
import net.artisanmc.modules.api.translation.MessageBundle;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The messages of {@code lang/<code>.yml} (bundle {@code lands} of Artisan's translations), in
 * each player's language. Placeholders go as {@code key, value} pairs and are inserted as plain
 * text: a land or player name cannot inject MiniMessage.
 */
public final class LandMessages {

    /** Prefix of the labels of {@link Action}, {@link ActionGroup} and {@link Flag} in the lang files. */
    public static final String ACTION = "permissions.";
    public static final String GROUP = "permission_groups.";
    public static final String FLAG = "flags.";

    private final MessageBundle bundle;

    public LandMessages(MessageBundle bundle) {
        this.bundle = bundle;
    }

    public void send(CommandSender to, String key, Object... placeholders) {
        bundle.send(to, key, map(placeholders));
    }

    public void actionBar(Player to, String key, Object... placeholders) {
        to.sendActionBar(component(to, key, placeholders));
    }

    public Component component(CommandSender viewer, String key, Object... placeholders) {
        return bundle.get(viewer, key, map(placeholders));
    }

    /** As a {@code §}-coded string, for the APIs that take one (Lamp's errors). */
    public String legacy(CommandSender viewer, String key, Object... placeholders) {
        return bundle.legacy(viewer, key, map(placeholders));
    }

    /**
     * The raw MiniMessage of {@code key} in the viewer's language (the default one without a
     * viewer, as in the editor's preview), else {@code fallback}: for the rows of the menus.
     */
    public String text(@Nullable CommandSender viewer, String key, String fallback) {
        String lang = viewer != null ? bundle.langOf(viewer) : bundle.getDefaultLang();
        String found = bundle.find(lang, key);
        if (found == null) found = bundle.find(bundle.getDefaultLang(), key);
        return found != null ? found : fallback;
    }

    /** {@link #text} with placeholders, still MiniMessage. */
    public String text(@Nullable CommandSender viewer, String key, String fallback, Object... placeholders) {
        return bundle.format(text(viewer, key, fallback), map(placeholders));
    }

    public String action(@Nullable CommandSender viewer, Action action) {
        return text(viewer, ACTION + action.name().toLowerCase(Locale.ROOT), action.getDisplayName());
    }

    public String group(@Nullable CommandSender viewer, ActionGroup group) {
        return text(viewer, GROUP + group.name().toLowerCase(Locale.ROOT), group.getDisplayName());
    }

    public String flag(@Nullable CommandSender viewer, Flag flag) {
        return text(viewer, FLAG + flag.name().toLowerCase(Locale.ROOT), flag.getDisplayName());
    }

    /** A land's name; the wilderness' in the viewer's language. */
    public String landName(@Nullable CommandSender viewer, Land land) {
        if (Land.WILDERNESS_ID.equals(land.getId())) return text(viewer, "land.wilderness", land.getName());
        return land.getName();
    }

    /** {@code lang/*.yml} read again (edits of the server's files). */
    public void reload() {
        bundle.reload();
    }

    private static Map<String, Object> map(Object... placeholders) {
        if (placeholders.length % 2 != 0) throw new IllegalArgumentException("Placeholders go in pairs (key, value)");
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < placeholders.length; i += 2) map.put((String) placeholders[i], String.valueOf(placeholders[i + 1]));
        return map;
    }
}
