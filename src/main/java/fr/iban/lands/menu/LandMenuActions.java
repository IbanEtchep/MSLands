package fr.iban.lands.menu;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.enums.LandType;
import fr.iban.lands.enums.LinkType;
import fr.iban.lands.events.PlayerLandFlagChangeEvent;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.SubLand;
import fr.iban.lands.utils.AreaSelector;
import fr.iban.lands.utils.CuboidSelector;
import net.artisanmc.modules.api.action.ActionContext;
import net.artisanmc.modules.api.action.ActionRuntime;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The actions of the land menus. Each one finds its land by id and checks again that the clicker
 * may manage it ({@link LandMenuData#canManage}), whatever the menu showed: an id typed into an
 * edited menu gives no right.
 */
public final class LandMenuActions {

    private final LandsPlugin plugin;
    private final LandRepository lands;
    private final LandService service;
    private final LandMessages messages;
    private final LandMenuData data;
    private final LandMenus menus;

    public LandMenuActions(LandsPlugin plugin, LandMenus menus) {
        this.plugin = plugin;
        this.lands = plugin.getLandRepository();
        this.service = plugin.getLandService();
        this.messages = plugin.getMessages();
        this.menus = menus;
        this.data = menus.data();
    }

    /** {@code scope}, {@code owner}, {@code parent}, {@code name}: a new land in the list it was asked from. */
    public ActionContext create(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Player player = ctx.getPlayer();
        LandMenus.Scope scope = LandMenus.Scope.parse(text(ctx, args, runtime, "scope"));
        if (scope == null) {
            runtime.warn(LandsModule.CREATE + ": scope is player, guild, system or sublands");
            return ctx;
        }
        UUID owner = LandMenuData.uuid(text(ctx, args, runtime, "owner"));
        Land parent = data.land(text(ctx, args, runtime, "parent"));
        if (!data.canOpen(player, scope, owner, parent)) {
            messages.send(player, "land.cannot_manage");
            return ctx;
        }
        String name = text(ctx, args, runtime, "name");
        switch (scope) {
            case PLAYER -> service.createLand(player, name, LandType.PLAYER, owner);
            case GUILD -> service.createLand(player, name, LandType.GUILD, owner);
            case SYSTEM -> service.createLand(player, name, LandType.SYSTEM, null);
            case SUBLANDS -> service.createSubland(player, parent, name);
        }
        return ctx;
    }

    /** {@code land}. */
    public ActionContext delete(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land == null) return ctx;
        if (land instanceof SubLand subLand && subLand.getSuperLand() != null) {
            subLand.getSuperLand().getSubLands().remove(subLand.getId());
        }
        lands.deleteLand(land);
        messages.send(ctx.getPlayer(), "land.deleted", "name", land.getName());
        return ctx;
    }

    /** {@code land}, {@code name}. */
    public ActionContext rename(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land != null) service.renameLand(land, ctx.getPlayer(), text(ctx, args, runtime, "name"));
        return ctx;
    }

    /** {@code land}: claims the chunk the player stands in. */
    public ActionContext claimHere(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land != null && !(land instanceof SubLand)) {
            service.claim(ctx.getPlayer(), new SChunk(ctx.getPlayer().getChunk()), land);
        }
        return ctx;
    }

    /** {@code land}: unclaims the chunk the player stands in, when it belongs to that land. */
    public ActionContext unclaimHere(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land == null) return ctx;
        SChunk chunk = new SChunk(ctx.getPlayer().getChunk());
        if (!land.equals(lands.getLandAt(chunk))) {
            messages.actionBar(ctx.getPlayer(), "unclaim.not_yours");
            return ctx;
        }
        service.unclaim(chunk);
        messages.actionBar(ctx.getPlayer(), "unclaim.done");
        return ctx;
    }

    /** {@code land}, {@code world}, {@code x}, {@code z}: one chunk of the chunk list, on this server. */
    public ActionContext unclaimChunk(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land == null) return ctx;
        SChunk chunk;
        try {
            chunk = new SChunk(plugin.getServerName(), text(ctx, args, runtime, "world"),
                    Integer.parseInt(text(ctx, args, runtime, "x")), Integer.parseInt(text(ctx, args, runtime, "z")));
        } catch (NumberFormatException e) {
            runtime.warn(LandsModule.UNCLAIM_CHUNK + ": x and z are chunk coordinates");
            return ctx;
        }
        if (!land.equals(lands.getLandAt(chunk))) {
            messages.actionBar(ctx.getPlayer(), "unclaim.not_yours");
            return ctx;
        }
        service.unclaim(chunk);
        messages.actionBar(ctx.getPlayer(), "unclaim.done");
        return ctx;
    }

    /** {@code land}: the chat selection of an area to (un)claim; leaving it reopens {@code lands:claims}. */
    public ActionContext selectArea(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land == null || land instanceof SubLand) return ctx;
        Player player = ctx.getPlayer();
        messages.send(player, "selection.start");
        new AreaSelector(player, land, plugin, () -> {
            messages.send(player, "selection.quit");
            menus.openClaims(player, land);
        }).startSelecting();
        return ctx;
    }

    /** {@code land}: a sub-land's area, selected in the chat; leaving it reopens {@code lands:manage}. */
    public ActionContext selectCuboid(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (!(land instanceof SubLand subLand)) return ctx;
        Player player = ctx.getPlayer();
        messages.send(player, "selection.start");
        new CuboidSelector(player, plugin, subLand, () -> {
            messages.send(player, "selection.quit");
            menus.openManage(player, subLand);
        }).startSelecting();
        return ctx;
    }

    /** {@code land}: the chunk map in the chat, its squares (un)claiming for that land. */
    public ActionContext showMap(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land != null && !(land instanceof SubLand)) plugin.getLandMap().display(ctx.getPlayer(), land);
        return ctx;
    }

    /** {@code land}, {@code player} (a name, as typed): opens that player's permissions. */
    public ActionContext openTrust(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land == null) return ctx;
        String name = text(ctx, args, runtime, "player");
        UUID target = playerId(name);
        if (target == null) {
            messages.send(ctx.getPlayer(), "trust.unknown_player", "player", name);
            return ctx;
        }
        menus.openTrustEdit(ctx.getPlayer(), land, target.toString());
        return ctx;
    }

    /** {@code land}, {@code target} ({@code global}, {@code guild} or a UUID), {@code permission}: toggles it. */
    public ActionContext togglePermission(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land == null) return ctx;
        String target = text(ctx, args, runtime, "target");
        Action action = LandMenuData.enumOf(Action.class, text(ctx, args, runtime, "permission"));
        var trust = LandMenuData.trust(land, target);
        if (action == null || trust == null) {
            runtime.warn(LandsModule.TOGGLE_PERMISSION + ": unknown target or permission");
            return ctx;
        }
        boolean grant = !trust.hasPermission(action);
        if (target.equalsIgnoreCase("global")) {
            if (grant) lands.addGlobalTrust(land, action); else lands.removeGlobalTrust(land, action);
        } else if (target.equalsIgnoreCase("guild")) {
            if (grant) lands.addGuildTrust(land, action); else lands.removeGuildTrust(land, action);
        } else {
            UUID uuid = LandMenuData.uuid(target);
            if (grant) lands.addTrust(land, uuid, action); else lands.removeTrust(land, uuid, action);
        }
        return ctx;
    }

    /** {@code land}, {@code target} (a UUID): removes every permission of that player. */
    public ActionContext untrust(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        UUID uuid = LandMenuData.uuid(text(ctx, args, runtime, "target"));
        if (land == null || uuid == null || !land.getTrusts().containsKey(uuid)) return ctx;
        for (Action action : List.copyOf(land.getTrust(uuid).getPermissions())) {
            lands.removeTrust(land, uuid, action);
        }
        return ctx;
    }

    /** {@code land}, {@code player} (a name, as typed). */
    public ActionContext ban(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        if (land == null) return ctx;
        String name = text(ctx, args, runtime, "player");
        UUID target = playerId(name);
        if (target == null) {
            messages.send(ctx.getPlayer(), "ban.unknown_player", "player", name);
            return ctx;
        }
        service.ban(ctx.getPlayer(), land, target);
        return ctx;
    }

    /** {@code land}, {@code player} (a UUID). */
    public ActionContext unban(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        UUID target = LandMenuData.uuid(text(ctx, args, runtime, "player"));
        if (land != null && target != null) service.unban(ctx.getPlayer(), land, target);
        return ctx;
    }

    /** {@code land}, {@code flag}: toggles it, through {@link PlayerLandFlagChangeEvent} as before. */
    public ActionContext toggleFlag(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        Flag flag = LandMenuData.enumOf(Flag.class, text(ctx, args, runtime, "flag"));
        if (land == null || flag == null || !flag.isEnabled(land)) return ctx;
        boolean enable = !land.hasFlag(flag);
        PlayerLandFlagChangeEvent event = new PlayerLandFlagChangeEvent(ctx.getPlayer(), land, flag, enable);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return ctx;
        if (enable) lands.addFlag(land, flag); else lands.removeFlag(land, flag);
        return ctx;
    }

    /** {@code land}, {@code type} ({@code trusts}, {@code globaltrust}, {@code bans}), {@code with}: a land's id. */
    public ActionContext link(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        LinkType type = LandMenuData.enumOf(LinkType.class, text(ctx, args, runtime, "type"));
        Land with = data.land(text(ctx, args, runtime, "with"));
        if (land == null || type == null || with == null) return ctx;
        if (land.equals(with)) {
            messages.send(ctx.getPlayer(), "link.self");
            return ctx;
        }
        if (!data.linkCandidates(ctx.getPlayer(), land, type).contains(with)) {
            messages.send(ctx.getPlayer(), "land.cannot_manage");
            return ctx;
        }
        lands.addLink(land, type, with);
        return ctx;
    }

    /** {@code land}, {@code type}. */
    public ActionContext unlink(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = managed(ctx, args, runtime);
        LinkType type = LandMenuData.enumOf(LinkType.class, text(ctx, args, runtime, "type"));
        if (land != null && type != null && land.getLinkedLand(type) != null) lands.removeLink(land, type);
        return ctx;
    }

    // ---------------------------------------------------------------- helpers

    /** The land {@code args.land} when the clicker may manage it; null, with a message, otherwise. */
    private @Nullable Land managed(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime) {
        Land land = data.land(text(ctx, args, runtime, "land"));
        if (land == null) {
            messages.send(ctx.getPlayer(), "land.not_found", "name", text(ctx, args, runtime, "land"));
            return null;
        }
        if (!data.canManage(ctx.getPlayer(), land)) {
            messages.send(ctx.getPlayer(), "land.cannot_manage");
            return null;
        }
        return land;
    }

    private static String text(ActionContext ctx, Map<String, Object> args, ActionRuntime runtime, String key) {
        Object value = args.get(key);
        if (value == null) return "";
        String text = runtime.text(ctx, value).trim();
        return text.startsWith("{") && text.endsWith("}") ? "" : text;
    }

    /** An online player, or one who has played here (from the server's cache: never a web lookup). */
    private static @Nullable UUID playerId(String name) {
        if (name.isBlank()) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null ? cached.getUniqueId() : null;
    }
}
