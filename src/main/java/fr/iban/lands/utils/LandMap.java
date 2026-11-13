package fr.iban.lands.utils;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.GuildLand;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.model.land.PlayerLand;
import fr.iban.lands.model.land.SystemLand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

public class LandMap {

    private final LandsPlugin plugin;
    private final LandRepository landRepository;
    private final Map<UUID, Land> landMapSelection = new HashMap<>();

    public LandMap(LandsPlugin plugin) {
        this.plugin = plugin;
        this.landRepository = plugin.getLandRepository();
    }

    private Component msg(Player player, String key, Object... placeholders) {
        return plugin.getMessages().component(player, key, placeholders);
    }

    public void display(Player player, Land land) {
        landMapSelection.put(player.getUniqueId(), land);
        List<Component> components = get(player, 15, 5, land);
        for (Component component : components) {
            player.sendMessage(component);
        }
    }

    public List<Component> get(Player player, int rangeX, int rangeZ, Land land) {
        List<Component> components = new ArrayList<>();
        String servername = LandsPlugin.getInstance().getServerName();
        final Chunk center = player.getChunk();
        final World world = center.getWorld();

        final int startX = center.getX() - rangeX;
        final int startZ = center.getZ() - rangeZ;
        final int endX = center.getX() + rangeX + 1;
        final int endZ = center.getZ() + rangeZ + 1;

        for (int i = 0; i < rangeZ * 2 + 1; i++) {
            components.add(Component.empty());
        }

        for (int x = startX; x < endX; x++) {
            int i = 0;
            for (int z = startZ; z < endZ; z++) {
                SChunk chunkXZ = new SChunk(servername, world.getName(), x, z);
                components.set(i, components.get(i).append(getSymbole(player, center, chunkXZ, land)));
                i++;
            }
        }

        Component headerAndFooter = Component.text("█".repeat(rangeX * 2 + 1), NamedTextColor.BLACK);
        components.add(0, headerAndFooter);

        return components;
    }

    private Component getSymbole(Player player, Chunk center, SChunk schunk, Land selectedLand) {
        TextColor baseColor = TextColor.fromHexString(HexColor.MARRON_CLAIR.getHex());
        TextColor marron = TextColor.fromHexString(HexColor.MARRON.getHex());
        Component baseComponent = Component.text("█").color(baseColor);
        ClickEvent unclaim = ClickEvent.runCommand("/land unclaimat " + schunk.getWorld() + " " + schunk.getX() + " " + schunk.getZ());
        Component newline = Component.newline();

        Land land = landRepository.getLandAt(schunk);

        if (land instanceof SystemLand sland) {
            Component hoverComponent = Component.text(plugin.getMessages().landName(player, sland))
                    .color(marron).decorate(TextDecoration.BOLD);
            if (landRepository.isWilderness(land)) {
                if (selectedLand != null) {
                    hoverComponent = hoverComponent.append(newline).append(msg(player, "map.click_claim"));
                    baseComponent = baseComponent.clickEvent(ClickEvent.runCommand("/land claimat " + schunk.getWorld() + " " + schunk.getX() + " " + schunk.getZ()));
                }
            } else {
                baseComponent = baseComponent.color(TextColor.fromHexString(HexColor.OLIVE.getHex()));
                if (sland.equals(selectedLand)) {
                    baseComponent = baseComponent.clickEvent(unclaim);
                }
            }
            baseComponent = baseComponent.hoverEvent(HoverEvent.showText(hoverComponent));
        } else if (land instanceof PlayerLand pland) {
            UUID owner = pland.getOwner();
            if (Objects.equals(owner, player.getUniqueId())) {
                baseComponent = baseComponent.color(NamedTextColor.DARK_GREEN);
                Component hoverComponent = msg(player, "map.land", "land", pland.getName())
                        .append(newline).append(msg(player, "map.owner_you"));
                if (selectedLand != null) {
                    hoverComponent = hoverComponent.append(newline).append(msg(player, "map.click_unclaim"));
                    baseComponent = baseComponent.clickEvent(unclaim);
                }
                baseComponent = baseComponent.hoverEvent(HoverEvent.showText(hoverComponent));
            } else {
                baseComponent = baseComponent.color(marron);
                String ownerName = Bukkit.getOfflinePlayer(pland.getOwner()).getName();
                Component hoverComponent = msg(player, "map.land_other", "land", pland.getName())
                        .append(newline).append(msg(player, "map.owner", "owner", ownerName != null ? ownerName : "?"));
                baseComponent = baseComponent.hoverEvent(HoverEvent.showText(hoverComponent));
            }
        } else if (land instanceof GuildLand guildLand) {
            boolean member = guildLand.isGuildMember(player.getUniqueId());
            baseComponent = baseComponent.color(member ? NamedTextColor.AQUA : marron);
            Component hoverComponent = msg(player, member ? "map.guild_land" : "map.guild_land_other", "land", guildLand.getName())
                    .append(newline).append(msg(player, "map.guild_owner", "guild", guildLand.getGuildName()));
            if (member && selectedLand != null) {
                hoverComponent = hoverComponent.append(newline).append(msg(player, "map.click_unclaim"));
                baseComponent = baseComponent.clickEvent(unclaim);
            }
            baseComponent = baseComponent.hoverEvent(HoverEvent.showText(hoverComponent));
        }

        if (center.getX() == schunk.getX() && center.getZ() == schunk.getZ()) {
            baseComponent = baseComponent.color(NamedTextColor.YELLOW);
        }

        return baseComponent;
    }

    public Map<UUID, Land> getLandMapSelection() {
        return landMapSelection;
    }
}
