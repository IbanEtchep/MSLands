package fr.iban.lands.utils;

import fr.iban.lands.LandsPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The keywords typed in the chat while selecting an area ({@code pos1}, {@code claim}…), which
 * Core's text inputs used to catch. The line is not sent to the chat; the handler runs on the
 * player's thread. Names and other free text are asked in Artisan dialogs instead.
 */
public final class ChatInputs implements Listener {

    private final Map<UUID, Consumer<String>> inputs = new ConcurrentHashMap<>();

    public void await(Player player, Consumer<String> handler) {
        inputs.put(player.getUniqueId(), handler);
    }

    public void cancel(Player player) {
        inputs.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        // Kept until cancel(): a selection reads several keywords.
        Consumer<String> handler = inputs.get(player.getUniqueId());
        if (handler == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        LandsPlugin.getInstance().getScheduler().runAtEntity(player, task -> handler.accept(text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inputs.remove(event.getPlayer().getUniqueId());
    }
}
