package fr.iban.lands.menu;

import fr.iban.lands.LandsPlugin;
import fr.iban.lands.enums.Action;
import fr.iban.lands.enums.Flag;
import fr.iban.lands.enums.LinkType;
import net.artisanmc.modules.api.ArtisanAPI;
import net.artisanmc.modules.api.playercommand.PlayerCommandDeclaration;
import net.artisanmc.modules.api.ArtisanModule;
import net.artisanmc.modules.api.action.ActionDeclaration;
import net.artisanmc.modules.api.action.ActionHandler;
import net.artisanmc.modules.api.datasource.DataFetcher;
import net.artisanmc.modules.api.datasource.DataSourceDeclaration;
import net.artisanmc.modules.api.datasource.DataSourceField;
import net.artisanmc.modules.api.model.ModelField;
import net.artisanmc.modules.api.model.ModelFieldType;
import net.artisanmc.modules.api.template.TemplateDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ArtisanLands inside Artisan:
 * <ul>
 *   <li>the folder {@code plugins/Artisan/modules/lands/}, with its {@code artisan-module.yml}
 *       (written when absent: without it Artisan does not read the folder as a project), its
 *       {@code lang/} (the messages, {@code LandsPlugin#registerMessages}) and the menus below;</li>
 *   <li>the system menus and dialogs, kept there by Artisan and opened by id ({@link LandMenus}):
 *       {@code lands:main} (from {@code /lands}), {@code manage}, {@code claims}, {@code chunks},
 *       {@code trusts}, {@code trust_edit}, {@code bans}, {@code unban_confirm}, {@code settings},
 *       {@code links}, {@code link_select}, {@code delete_confirm} (from {@code /land delete}), the
 *       dialogs {@code create}, {@code rename}, {@code trust_add} and {@code ban_add};</li>
 *   <li>the data sources {@code lands:*} ({@link LandMenuData}) and the actions {@code lands:*}
 *       ({@link LandMenuActions}). Their names and arguments are public: an admin's menus use them.</li>
 * </ul>
 */
public final class LandsModule implements ArtisanModule {

    public static final String ID = "lands";
    /** The module's folder: the {@code lands} of {@code lands:main}. */
    public static final String FOLDER = "lands";

    /** The shipped menus and dialogs: raise {@link #SYSTEM_VERSION} whenever one of them changes. */
    static final String SYSTEM = "lands_menus";
    static final int SYSTEM_VERSION = 1;
    static final String SYSTEM_ROOT = "artisan-system/lands";
    static final List<String> SYSTEM_FILES = List.of(
            "menus/main.yaml",
            "menus/manage.yaml",
            "menus/claims.yaml",
            "menus/chunks.yaml",
            "menus/trusts.yaml",
            "menus/trust_edit.yaml",
            "menus/bans.yaml",
            "menus/unban_confirm.yaml",
            "menus/settings.yaml",
            "menus/links.yaml",
            "menus/link_select.yaml",
            "menus/delete_confirm.yaml",
            "dialogs/create.yaml",
            "dialogs/rename.yaml",
            "dialogs/trust_add.yaml",
            "dialogs/ban_add.yaml");

    public static final String CREATE = "lands:create";
    public static final String DELETE = "lands:delete";
    public static final String RENAME = "lands:rename";
    public static final String CLAIM_HERE = "lands:claim_here";
    public static final String UNCLAIM_HERE = "lands:unclaim_here";
    public static final String UNCLAIM_CHUNK = "lands:unclaim_chunk";
    public static final String SELECT_AREA = "lands:select_area";
    public static final String SELECT_CUBOID = "lands:select_cuboid";
    public static final String SHOW_MAP = "lands:show_map";
    public static final String OPEN_TRUST = "lands:open_trust";
    public static final String TOGGLE_PERMISSION = "lands:toggle_permission";
    public static final String UNTRUST = "lands:untrust";
    public static final String BAN = "lands:ban";
    public static final String UNBAN = "lands:unban";
    public static final String TOGGLE_FLAG = "lands:toggle_flag";
    public static final String LINK = "lands:link";
    public static final String UNLINK = "lands:unlink";

    /** {@code /land}, {@code /lands}…: registered from {@link #onEnable}, their files go in the module's folder. */
    private final List<PlayerCommandDeclaration> commands;
    private final LandMenuData data;
    private final LandMenuActions actions;
    private final Logger logger;

    public LandsModule(LandsPlugin plugin, List<PlayerCommandDeclaration> commands, Logger logger) {
        this.commands = List.copyOf(commands);
        this.data = plugin.getMenus().data();
        this.actions = new LandMenuActions(plugin, plugin.getMenus());
        this.logger = logger;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getSchemaVersion() {
        return "lands/v1";
    }

    @Override
    public void onEnable(ArtisanAPI api) {
        claimFolder(api);
        // Registered from here, the commands belong to the module: their file goes in its folder.
        commands.forEach(api.getPlayerCommands()::register);
        registerSystemFiles(api);
        registerDataSources(api);
        registerActions(api);
    }

    @Override
    public void onDisable() {
        // Artisan removes what this module registered.
    }

    /** Before the system files: Artisan writes them into the module's folder. */
    private void claimFolder(ArtisanAPI api) {
        try {
            File folder = api.getProjectRoots().moduleFolder(FOLDER, ID, null);
            if (writeModuleFile(folder)) logger.info("Wrote " + MODULE_FILE_NAMES.getFirst() + " in " + folder.getPath()
                    + ": Artisan now reads the land menus there.");
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException | IOException e) {
            logger.log(Level.WARNING, "The folder plugins/Artisan/modules/" + FOLDER + " is not available: "
                    + "the land menus will not open", e);
        }
    }

    /** The module file under each name Artisan reads, the one it looks for first first. */
    static final List<String> MODULE_FILE_NAMES = List.of("artisan-module.yml", "manifest.yml", "manifest.yaml");

    static final String MODULE_FILE = """
            schema: module/v1
            default_lang: fr
            languages:
              - fr
              - en
            """;

    /** Writes the folder's {@code artisan-module.yml} when it has none under any name; the admin's is never touched. */
    static boolean writeModuleFile(File folder) throws IOException {
        for (String name : MODULE_FILE_NAMES) {
            if (new File(folder, name).exists()) return false;
        }
        Files.createDirectories(folder.toPath());
        File target = new File(folder, MODULE_FILE_NAMES.getFirst());
        File tmp = new File(folder, "." + target.getName() + ".tmp");
        Files.writeString(tmp.toPath(), MODULE_FILE, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    private void registerSystemFiles(ArtisanAPI api) {
        try {
            api.getTemplates().register(TemplateDeclaration
                    .fromResources(getClass().getClassLoader(), SYSTEM_ROOT, SYSTEM, SYSTEM_VERSION, SYSTEM_FILES)
                    .title("Land menus")
                    .system()
                    .build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.log(Level.WARNING, "The land menus are not available", e);
        }
    }

    private void registerDataSources(ArtisanAPI api) {
        List<DataSourceField> land = List.of(
                field("id", ModelFieldType.string(), "What the lands:* actions take as land"),
                field("name", ModelFieldType.string(), null),
                field("type", ModelFieldType.string(), "player, guild, system or subland"),
                field("chunks", ModelFieldType.integer(), "Chunks on this server"),
                field("trusts", ModelFieldType.integer(), "Players with permissions"),
                field("bans", ModelFieldType.integer(), "Banned players"));

        source(api, "lands:lands", data::lands, land);

        List<DataSourceField> one = new ArrayList<>(land);
        one.addAll(List.of(
                field("subland", ModelFieldType.bool(), null),
                field("can_sublands", ModelFieldType.bool(), "Not a sub-land, and the viewer has lands.sublands"),
                field("guild_trust", ModelFieldType.bool(), "Guild permissions apply: an owned land, a viewer in a guild"),
                field("sublands", ModelFieldType.integer(), null),
                field("area_set", ModelFieldType.bool(), "A sub-land's area is defined on this server"),
                field("area", ModelFieldType.string(), "world x y z → x y z"),
                field("area_server", ModelFieldType.string(), "The server of a sub-land's area"),
                field("area_elsewhere", ModelFieldType.bool(), "Its area is on another server")));
        source(api, "lands:land", data::land, one);

        source(api, "lands:limits", data::limits, List.of(
                field("used", ModelFieldType.string(), "Chunks used on the network, … while read"),
                field("max", ModelFieldType.string(), null),
                field("guild", ModelFieldType.bool(), null)));

        List<DataSourceField> player = List.of(
                field("id", ModelFieldType.string(), "The player's UUID"),
                field("name", ModelFieldType.string(), null));
        source(api, "lands:trusts", data::trusts, player);
        source(api, "lands:bans", data::bans, player);

        source(api, "lands:trust_target", data::trustTarget, List.of(
                field("kind", ModelFieldType.string(), "player, global or guild"),
                field("name", ModelFieldType.string(), "The player's name")));

        source(api, "lands:permissions", data::permissions, List.of(
                field("key", ModelFieldType.string(), "An action, or a group for kind group"),
                field("kind", ModelFieldType.string(), "action or group"),
                field("name", ModelFieldType.string(), "In the viewer's language"),
                field("icon", ModelFieldType.string(), "A material"),
                field("granted", ModelFieldType.bool(), "Granted (every action of a group)"),
                field("granted_count", ModelFieldType.integer(), null),
                field("total", ModelFieldType.integer(), null)));

        source(api, "lands:flags", data::flags, List.of(
                field("key", ModelFieldType.string(), "What lands:toggle_flag takes"),
                field("name", ModelFieldType.string(), "In the viewer's language"),
                field("icon", ModelFieldType.string(), "A material"),
                field("enabled", ModelFieldType.bool(), null)));

        source(api, "lands:links", data::links, List.of(
                field("key", ModelFieldType.string(), "trusts, globaltrust or bans"),
                field("linked", ModelFieldType.bool(), null),
                field("with", ModelFieldType.string(), "The linked land's name")));

        source(api, "lands:link_candidates", data::linkCandidates, land);

        source(api, "lands:chunks", data::chunks, List.of(
                field("server", ModelFieldType.string(), null),
                field("world", ModelFieldType.string(), null),
                field("x", ModelFieldType.integer(), "Chunk coordinates"),
                field("z", ModelFieldType.integer(), null),
                field("block_x", ModelFieldType.integer(), null),
                field("block_z", ModelFieldType.integer(), null)));
    }

    private void registerActions(ArtisanAPI api) {
        ModelField landArg = new ModelField("land", ModelFieldType.string(), true)
                .withHelp("A land's id. In the shipped menus: {land} or {l.id}.");
        ModelField name = new ModelField("name", ModelFieldType.string(), true)
                .withHelp("16 characters at most, no spaces. In the shipped dialogs: {name}.");
        ModelField playerName = new ModelField("player", ModelFieldType.string(), true)
                .withHelp("A player's name, as typed.");

        action(api, CREATE, List.of(
                new ModelField("scope", ModelFieldType.enumOf(List.of("player", "guild", "system", "sublands")), true),
                new ModelField("owner", ModelFieldType.string()).withHelp("The player's or guild's UUID"),
                new ModelField("parent", ModelFieldType.string()).withHelp("For sublands: the parent land's id"),
                name), actions::create);
        action(api, DELETE, List.of(landArg), actions::delete);
        action(api, RENAME, List.of(landArg, name), actions::rename);
        action(api, CLAIM_HERE, List.of(landArg), actions::claimHere);
        action(api, UNCLAIM_HERE, List.of(landArg), actions::unclaimHere);
        action(api, UNCLAIM_CHUNK, List.of(landArg,
                new ModelField("world", ModelFieldType.string(), true),
                new ModelField("x", ModelFieldType.string(), true).withHelp("Chunk coordinate"),
                new ModelField("z", ModelFieldType.string(), true).withHelp("Chunk coordinate")), actions::unclaimChunk);
        action(api, SELECT_AREA, List.of(landArg), actions::selectArea);
        action(api, SELECT_CUBOID, List.of(landArg), actions::selectCuboid);
        action(api, SHOW_MAP, List.of(landArg), actions::showMap);
        action(api, OPEN_TRUST, List.of(landArg, playerName), actions::openTrust);
        action(api, TOGGLE_PERMISSION, List.of(landArg,
                new ModelField("target", ModelFieldType.string(), true).withHelp("global, guild or a player's UUID"),
                new ModelField("permission", ModelFieldType.enumOf(keys(Action.values())), true)), actions::togglePermission);
        action(api, UNTRUST, List.of(landArg,
                new ModelField("target", ModelFieldType.string(), true).withHelp("A player's UUID")), actions::untrust);
        action(api, BAN, List.of(landArg, playerName), actions::ban);
        action(api, UNBAN, List.of(landArg,
                new ModelField("player", ModelFieldType.string(), true).withHelp("A player's UUID")), actions::unban);
        action(api, TOGGLE_FLAG, List.of(landArg,
                new ModelField("flag", ModelFieldType.enumOf(keys(Flag.values())), true)), actions::toggleFlag);
        ModelField linkType = new ModelField("type", ModelFieldType.enumOf(keys(LinkType.values())), true);
        action(api, LINK, List.of(landArg, linkType,
                new ModelField("with", ModelFieldType.string(), true).withHelp("The linked land's id")), actions::link);
        action(api, UNLINK, List.of(landArg, linkType), actions::unlink);
    }

    private void source(ArtisanAPI api, String ref, DataFetcher fetcher, List<DataSourceField> fields) {
        try {
            api.getDataSources().register(new DataSourceDeclaration(ref, fetcher, fields));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Data source " + ref + " is not available", e);
        }
    }

    private void action(ArtisanAPI api, String name, List<ModelField> args, ActionHandler handler) {
        try {
            api.getActionRegistry().register(new ActionDeclaration(name, args, handler));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Action " + name + " is not available", e);
        }
    }

    private static DataSourceField field(String name, ModelFieldType type, String description) {
        return new DataSourceField(name, type, description);
    }

    private static List<String> keys(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
    }
}
