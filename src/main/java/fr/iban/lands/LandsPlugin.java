package fr.iban.lands;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.commands.GuildClaimsCommand;
import fr.iban.lands.commands.LandCommand;
import fr.iban.lands.commands.LandsCommand;
import fr.iban.lands.commands.MaxClaimsCommand;
import fr.iban.lands.commands.MiscellaneousCommands;
import fr.iban.lands.guild.AbstractGuildDataAccess;
import fr.iban.lands.guild.ArtisanGuildsDataAccess;
import fr.iban.lands.listeners.*;
import fr.iban.lands.menu.LandMenus;
import fr.iban.lands.menu.LandPlaceholders;
import fr.iban.lands.menu.LandsModule;
import fr.iban.lands.messages.LandMessages;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.service.LandRepositoryImpl;
import fr.iban.lands.service.LandServiceImpl;
import fr.iban.lands.storage.CoreDatabase;
import fr.iban.lands.storage.LandSchema;
import fr.iban.lands.storage.LandTables;
import fr.iban.lands.storage.LegacyImport;
import fr.iban.lands.storage.SqlStorage;
import fr.iban.lands.sync.LandSync;
import fr.iban.lands.task.PotionEffectTask;
import fr.iban.lands.utils.ChatInputs;
import fr.iban.lands.utils.LandMap;
import fr.iban.lands.utils.SeeClaims;
import net.artisanmc.modules.api.ArtisanAPI;
import net.artisanmc.modules.api.playercommand.PlayerCommandDeclaration;
import net.artisanmc.modules.api.translation.MessageBundle;
import net.artisanmc.modules.api.translation.MessageBundleDeclaration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * ArtisanLands (formerly MSLands). Artisan is required: it lends the database, the server's name,
 * the translations and the menus. Without a compatible Artisan, or without its database, the
 * plugin disables itself and says why: no player ever plays on unprotected lands.
 *
 * <p>The class keeps its name and {@link #getInstance()}, {@link #getLandRepository()}: Warps and
 * MSBoosts call them.
 */
public final class LandsPlugin extends JavaPlugin {

    /** Lowest Artisan API this plugin compiles against: database, translations in the module's folder, system menus. */
    public static final String MIN_API_VERSION = "1.0.0";

    /** The namespace of the tables ({@code artisan_lands_*} by default) and of the messages. */
    public static final String NAMESPACE = "lands";

    private static LandsPlugin instance;
    private final List<UUID> bypass = new ArrayList<>();
    private final List<UUID> debugPlayers = new ArrayList<>();
    private AbstractGuildDataAccess guildDataAccess;
    private Economy econ = null;
    private ExecutorService singleThreadExecutor;
    private FoliaLib foliaLib;
    private @Nullable ArtisanAPI api;
    private String serverName = "";

    private LandRepository landRepository;
    private LandService landService;
    private LandMessages messages;
    private LandMenus menus;
    private final ChatInputs chatInputs = new ChatInputs();

    private final Map<UUID, SeeClaims> seeClaims = new HashMap<>();
    private LandMap landMap;

    @Override
    public void onEnable() {
        instance = this;
        api = resolveArtisan();
        if (api == null) {
            disable();
            return;
        }

        copyLegacyConfig();
        saveDefaultConfig();
        reloadConfig();
        foliaLib = new FoliaLib(this);
        serverName = resolveServerName(api);

        try {
            DataSource dataSource = api.getDatabase().dataSource(this);
            LandTables tables = new LandTables(api.getDatabase().tablePrefix(NAMESPACE));
            LandSchema.Result schema = new LandSchema(dataSource, api.getDatabase().getDialect(), tables).migrate();
            if (schema.created()) getLogger().info("Land tables created (" + tables.prefix() + "*).");
            if (schema.upgraded()) getLogger().info("Land tables upgraded from version " + schema.fromVersion()
                    + " to " + schema.toVersion() + ".");
            importLegacy(dataSource, tables);

            singleThreadExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ArtisanLands-database"));
            // Blocking, before any listener: until the lands are in memory, every chunk would be wilderness.
            landRepository = new LandRepositoryImpl(this, new SqlStorage(dataSource, tables, serverName, getLogger()));
        } catch (SQLException | IllegalStateException | IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "The database is not available, ArtisanLands is disabled. "
                    + "Check plugins/Artisan/environment.yml.", e);
            disable();
            return;
        }

        messages = new LandMessages(registerMessages(api));
        landService = new LandServiceImpl(this);
        landMap = new LandMap(this);
        menus = new LandMenus(api, this);

        setupEconomy();
        hookGuilds();

        // The other servers' changes to the lands, and this one's to them (MSLands had none).
        try {
            new LandSync(this, (LandRepositoryImpl) landRepository, api.getNetwork()).start();
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Lands are not synchronised with the other servers", e);
        }
        // %lands_…%, for PlaceholderAPI too; removed by Artisan when this plugin disables.
        try {
            api.getPlaceholders().register(LandPlaceholders.NAMESPACE, new LandPlaceholders(this, menus.data()), true);
        } catch (RuntimeException e) {
            getLogger().warning("%" + LandPlaceholders.NAMESPACE + "_…% not registered: " + e.getMessage());
        }

        registerListeners(
                chatInputs,
                new BlockBreakListener(this),
                new BlockPlaceListener(this),
                new CommandListener(this),
                new DamageListeners(this),
                new DropListener(this),
                new EntityBlockDamageListener(this),
                new EntitySpawnListener(this),
                new ExplodeListeners(this),
                new FireListener(this),
                new HangingListeners(this),
                new InteractListener(this),
                new JoinQuitListeners(this),
                new LandListeners(this),
                new PistonListeners(this),
                new PlayerMoveListener(this),
                new PlayerTakeLecternBookListener(this),
                new PortalListeners(this),
                new ServiceListeners(this),
                new SignListeners(this),
                new TeleportListener(this)
        );
        TeleportListener.hookCoreTeleports(this);

        if (getServer().getPluginManager().getPlugin("QuickShop-Hikari") != null) {
            getServer().getPluginManager().registerEvents(new ShopListeners(this), this);
            getLogger().info("QuickShop hooked.");
        }

        // The menus, their data sources and actions, and the commands: registered by the module, each
        // command is a file of its folder (commands/land.yaml…), edited in the editor.
        List<PlayerCommandDeclaration> commands = commands();
        try {
            api.getModules().register(new LandsModule(this, commands, getLogger()));
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "The land menus are not available", e);
        }
        // Without the module, the commands still work, as declared: Artisan removes them when this plugin disables.
        for (PlayerCommandDeclaration command : commands) {
            if (api.getPlayerCommands().find(command.getId()) == null) api.getPlayerCommands().register(command);
        }

        PotionEffectTask potionEffectTask = new PotionEffectTask(this);
        getScheduler().runTimer(potionEffectTask, 100, 100);
        getLogger().info(landRepository.getLands().size() + " lands and " + landRepository.getChunks().size()
                + " chunks loaded for server '" + serverName + "' (Artisan API " + api.getApiVersion() + ").");
    }

    @Override
    public void onDisable() {
        seeClaims.values().forEach(SeeClaims::stop);
        seeClaims.clear();
        if (api != null && api.getModules().has(LandsModule.ID)) api.getModules().unregister(LandsModule.ID);
        if (singleThreadExecutor != null) {
            // Queued writes finish before the pool closes (Artisan waits 5 seconds).
            singleThreadExecutor.shutdown();
            try {
                if (!singleThreadExecutor.awaitTermination(4, TimeUnit.SECONDS)) {
                    getLogger().warning("Some land changes were still being saved at shutdown.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        singleThreadExecutor = null;
        api = null;
    }

    /** /land, /lands, /guildclaims, /addmaxclaim, /removemaxclaim, /getmaxclaim and /sayinchat. */
    private List<PlayerCommandDeclaration> commands() {
        List<PlayerCommandDeclaration> commands = new ArrayList<>();
        commands.add(new LandCommand(this).declaration());
        commands.add(new LandsCommand(this).declaration());
        commands.add(new GuildClaimsCommand(this).declaration());
        commands.addAll(new MaxClaimsCommand(this).declarations());
        commands.add(new MiscellaneousCommands().declaration());
        return commands;
    }

    /**
     * The messages, in plugins/Artisan/modules/lands/lang/ beside the menus (edited in the editor),
     * else in this plugin's folder when Artisan gives no module folder.
     */
    private MessageBundle registerMessages(ArtisanAPI api) {
        MessageBundleDeclaration files = MessageBundleDeclaration.files(this, NAMESPACE, "fr", "en")
                .withDefaultLang(getConfig().getString("language", "fr"));
        File folder = null;
        try {
            folder = api.getProjectRoots().moduleFolder(LandsModule.FOLDER, LandsModule.ID, null);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "No folder plugins/Artisan/modules/" + LandsModule.FOLDER
                    + ": the messages stay in plugins/" + getName() + "/lang/", e);
        }
        return api.getTranslations().register(folder != null ? files.inFolder(folder) : files);
    }

    /** config.yml of plugins/MSLands/, copied once: same keys. The original stays for a rollback. */
    private void copyLegacyConfig() {
        Path legacy = getDataFolder().toPath().resolveSibling("MSLands").resolve("config.yml");
        Path target = getDataFolder().toPath().resolve("config.yml");
        if (!Files.isRegularFile(legacy) || Files.exists(target)) return;
        try {
            Files.createDirectories(target.getParent());
            Files.copy(legacy, target);
            getLogger().info("Copied plugins/MSLands/config.yml to plugins/" + getName() + "/config.yml.");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not copy plugins/MSLands/config.yml", e);
        }
    }

    /**
     * The name in the column {@code server} of the chunks: {@code server-name} of config.yml, else
     * Artisan's. Under MSLands it was Core's servername: the two must match for the old claims.
     */
    private String resolveServerName(ArtisanAPI api) {
        String configured = getConfig().getString("server-name", "");
        if (configured != null && !configured.isBlank()) return configured.trim();
        return api.getNetwork().getServerName();
    }

    /**
     * MSLands' tables come from Core's database when Core is enabled, else from Artisan's own
     * database when they are there. Once only; an error stops the start, so that players never
     * play on an empty copy.
     */
    private void importLegacy(DataSource target, LandTables tables) throws SQLException {
        DataSource core = CoreDatabase.find(getServer().getPluginManager().getPlugin("Core"));
        DataSource source = core != null ? core : target;
        String label = core != null ? "the Core plugin's database" : "Artisan's database";
        LegacyImport.Result result = new LegacyImport(tables).run(source, label, target);
        switch (result.outcome()) {
            case IMPORTED -> getLogger().info("MSLands data imported from " + label + ": " + result.counts()
                    + ". MSLands' tables were left as they are.");
            case TARGET_NOT_EMPTY -> getLogger().warning("MSLands tables found in " + label + ", but ArtisanLands "
                    + "already holds lands: nothing imported. Empty " + tables.prefix() + "* to import them.");
            case NO_SOURCE, ALREADY_IMPORTED -> { }
        }
    }

    private @Nullable ArtisanAPI resolveArtisan() {
        try {
            ArtisanAPI found = getServer().getServicesManager().load(ArtisanAPI.class);
            if (found == null) {
                getLogger().severe("Artisan is required but its API is not available. "
                        + "Install Artisan (API " + MIN_API_VERSION + "+) next to ArtisanLands.");
                return null;
            }
            if (!found.isCompatibleWith(MIN_API_VERSION)) {
                getLogger().severe("Artisan API " + found.getApiVersion() + " is too old: ArtisanLands needs "
                        + MIN_API_VERSION + "+ (same major). Update Artisan.");
                return null;
            }
            return found;
        } catch (LinkageError e) {
            getLogger().log(Level.SEVERE, "Installed Artisan is incompatible: ArtisanLands needs API "
                    + MIN_API_VERSION + "+.", e);
            return null;
        }
    }

    private void disable() {
        getServer().getPluginManager().disablePlugin(this);
    }

    public void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            econ = null;
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        econ = rsp != null ? rsp.getProvider() : null;
    }

    public Economy getEconomy() {
        return econ;
    }

    /** ArtisanGuilds' API may arrive after this plugin is enabled: its hook is kept even before. */
    private void hookGuilds() {
        if (!getConfig().getBoolean("guild-lands-enabled", false)) {
            return;
        }
        PluginManager pluginManager = getServer().getPluginManager();
        if (pluginManager.getPlugin("ArtisanGuilds") == null) {
            return;
        }
        try {
            ArtisanGuildsDataAccess artisanGuilds = new ArtisanGuildsDataAccess(this);
            artisanGuilds.load();
            this.guildDataAccess = artisanGuilds;
        } catch (LinkageError e) {
            getLogger().log(Level.WARNING, "ArtisanGuilds is installed but its API is not reachable: no guild lands", e);
        }
    }

    public boolean isGuildsHookEnabled() {
        return guildDataAccess != null && guildDataAccess.isEnabled();
    }

    public static LandsPlugin getInstance() {
        return instance;
    }

    private void registerListeners(Listener... listeners) {
        PluginManager pm = Bukkit.getPluginManager();
        for (Listener listener : listeners) {
            pm.registerEvents(listener, this);
        }
    }

    public List<UUID> getBypass() {
        return bypass;
    }

    public boolean isBypassing(Player player) {
        return isBypassing(player.getUniqueId());
    }

    public boolean isBypassing(UUID uuid) {
        return getBypass().contains(uuid);
    }

    public void setBypassing(UUID uuid, boolean value) {
        if (value) {
            if (!bypass.contains(uuid)) bypass.add(uuid);
        } else {
            bypass.remove(uuid);
        }
    }

    public List<UUID> getDebugPlayers() {
        return debugPlayers;
    }

    public boolean isInDebugMode(Player player) {
        return isInDebugMode(player.getUniqueId());
    }

    public boolean isInDebugMode(UUID uuid) {
        return getDebugPlayers().contains(uuid);
    }

    public void setDebugging(UUID uuid, boolean value) {
        if (value) {
            if (!debugPlayers.contains(uuid)) debugPlayers.add(uuid);
        } else {
            debugPlayers.remove(uuid);
        }
    }

    public AbstractGuildDataAccess getGuildDataAccess() {
        return guildDataAccess;
    }

    /** This server in the lands' tables: {@code server-name} of config.yml, else Artisan's name. */
    public String getServerName() {
        return serverName;
    }

    /** On ArtisanLands' database thread, in order. */
    public void runAsyncQueued(Runnable runnable) {
        ExecutorService executor = singleThreadExecutor;
        if (executor == null || executor.isShutdown()) {
            runnable.run();
            return;
        }
        executor.execute(() -> {
            try {
                runnable.run();
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "A land change could not be saved", e);
            }
        });
    }

    public LandRepository getLandRepository() {
        return landRepository;
    }

    public LandService getLandService() {
        return landService;
    }

    public LandMessages getMessages() {
        return messages;
    }

    public LandMenus getMenus() {
        return menus;
    }

    public ChatInputs getChatInputs() {
        return chatInputs;
    }

    public LandMap getLandMap() {
        return landMap;
    }

    public Map<UUID, SeeClaims> getSeeClaims() {
        return seeClaims;
    }

    public PlatformScheduler getScheduler() {
        return foliaLib.getScheduler();
    }
}
