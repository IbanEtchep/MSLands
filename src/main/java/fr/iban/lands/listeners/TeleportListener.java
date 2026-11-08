package fr.iban.lands.listeners;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.logging.Level;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.events.PlayerLandEnterEvent;
import fr.iban.lands.model.land.Land;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class TeleportListener implements Listener {

    private final LandRepository landRepository;

    public TeleportListener(LandsPlugin plugin) {
        this.landRepository = plugin.getLandRepository();
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        Land landTo = landRepository.getLandAt(to);
        Land landFrom = landRepository.getLandAt(from);

        Player player = event.getPlayer();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();

        if (cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT
                && !landTo.isBypassing(player, Action.CHORUS_TELEPORT)) {
            event.setCancelled(true);
            return;
        }

        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && !landTo.isBypassing(player, Action.ENDER_PEARL_TELEPORT)) {
            event.setCancelled(true);
            return;
        }

        PlayerLandEnterEvent enter = new PlayerLandEnterEvent(player, landFrom, landTo);
        Bukkit.getPluginManager().callEvent(enter);
        if (enter.isCancelled()) {
            event.setCancelled(true);
        }
    }

    /**
     * Core's {@code PlayerPreTeleportEvent} (its teleports and /home, /warp delays), listened to by
     * name while Core is installed: no delay from a land with {@link Flag#INSTANT_TELEPORT}.
     * Nothing without Core.
     */
    @SuppressWarnings("unchecked")
    public static void hookCoreTeleports(LandsPlugin plugin) {
        Plugin core = plugin.getServer().getPluginManager().getPlugin("Core");
        if (core == null || !core.isEnabled()) return;
        Class<? extends Event> type;
        Method getPlayer;
        Method setDelay;
        try {
            Class<?> found = Class.forName("fr.iban.bukkitcore.event.PlayerPreTeleportEvent", true, core.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(found)) return;
            type = (Class<? extends Event>) found;
            getPlayer = type.getMethod("getPlayer");
            setDelay = type.getMethod("setDelay", int.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().info("Core's teleport event not found: the INSTANT_TELEPORT flag does not apply to its teleports.");
            return;
        }
        LandRepository lands = plugin.getLandRepository();
        EventExecutor executor = (listener, event) -> {
            if (!type.isInstance(event)) return;
            try {
                if (getPlayer.invoke(event) instanceof Player player
                        && lands.getLandAt(player.getLocation()).hasFlag(Flag.INSTANT_TELEPORT)) {
                    setDelay.invoke(event, 0);
                }
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().log(Level.FINE, "Core's teleport event could not be read", e);
            }
        };
        plugin.getServer().getPluginManager().registerEvent(type, new Listener() { }, EventPriority.NORMAL, executor, plugin);
    }
}
