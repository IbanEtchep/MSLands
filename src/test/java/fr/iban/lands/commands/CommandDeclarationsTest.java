package fr.iban.lands.commands;

import fr.iban.lands.LandsPlugin;
import net.artisanmc.modules.api.playercommand.PlayerCommandDeclaration;
import net.artisanmc.modules.api.playercommand.PlayerCommandRegistry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The commands as Artisan registers them: valid declarations, and the names, aliases and
 * permissions MSLands had with Lamp (players and admins type the same commands).
 */
class CommandDeclarationsTest {

    private static List<PlayerCommandDeclaration> commands;

    @BeforeAll
    static void declare() {
        LandsPlugin plugin = mock(LandsPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        commands = new ArrayList<>();
        commands.add(new LandCommand(plugin).declaration());
        commands.add(new LandsCommand(plugin).declaration());
        commands.add(new GuildClaimsCommand(plugin).declaration());
        commands.addAll(new MaxClaimsCommand(plugin).declarations());
        commands.add(new MiscellaneousCommands().declaration());
    }

    @Test
    void artisanAcceptsThem() {
        PlayerCommandRegistry registry = new PlayerCommandRegistry();
        commands.forEach(registry::register);
        assertEquals(commands.size(), registry.declarations().size());
    }

    @Test
    void sameCommandsAsMsLands() {
        Map<String, List<String>> roots = commands.stream().collect(Collectors.toMap(
                PlayerCommandDeclaration::getName, PlayerCommandDeclaration::getAliases, (a, b) -> a, TreeMap::new));
        assertEquals(Map.of(
                "land", List.of("l"),
                "lands", List.of(),
                "guildclaims", List.of(),
                "addmaxclaim", List.of(),
                "removemaxclaim", List.of(),
                "getmaxclaim", List.of(),
                "sayinchat", List.of()), roots);

        Map<String, String> land = paths(find("land"), "");
        assertEquals(Set.of("help", "menu", "create", "delete", "claim", "unclaim", "forceunclaim", "claimat",
                "unclaimat", "map", "seeclaims", "kick", "giveclaims", "bypass", "admin", "admin setspawn",
                "admin debug", "admin chunkinfo", "admin setdefaultworldclaim", "admin effect", "admin effect add",
                "admin effect remove", "admin effect list", "admin command", "admin command add",
                "admin command remove", "admin command list"), land.keySet());
        assertEquals(Set.of("menu", "system", "guild"), paths(find("lands"), "").keySet());
        assertEquals(Set.of("info", "buy"), paths(find("guildclaims"), "").keySet());
    }

    @Test
    void samePermissions() {
        Map<String, String> land = paths(find("land"), "");
        land.forEach((path, permission) -> {
            String expected = path.startsWith("admin") || path.equals("forceunclaim") ? "lands.admin"
                    : path.equals("bypass") ? "lands.bypass" : "";
            assertEquals(expected, permission, "/land " + path);
        });
        assertEquals("lands.admin", paths(find("lands"), "").get("system"));
        for (String admin : List.of("addmaxclaim", "removemaxclaim", "getmaxclaim")) {
            assertEquals("lands.admin", find(admin).getPermission(), "/" + admin);
        }
    }

    private static PlayerCommandDeclaration find(String name) {
        return commands.stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }

    /** Each sub-command by its path ({@code admin effect add}) → its permission, "" for none. */
    private static Map<String, String> paths(PlayerCommandDeclaration node, String prefix) {
        Map<String, String> paths = new TreeMap<>();
        for (PlayerCommandDeclaration child : node.getChildren()) {
            String path = prefix + child.getName();
            paths.put(path, child.getPermission() == null ? "" : child.getPermission());
            paths.putAll(paths(child, path + " "));
        }
        return paths;
    }
}
