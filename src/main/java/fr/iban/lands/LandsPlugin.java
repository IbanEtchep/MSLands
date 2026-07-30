package fr.iban.lands;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import fr.iban.bukkitcore.CoreBukkitPlugin;
import fr.iban.lands.api.LandRepository;
import fr.iban.lands.api.LandService;
import fr.iban.lands.commands.*;
import fr.iban.lands.commands.parametertypes.LandParameterType;
import fr.iban.lands.guild.AbstractGuildDataAccess;
import fr.iban.lands.guild.GuildsDataAccess;
import fr.iban.lands.integration.bluemap.BlueMapClaimVisualization;
import fr.iban.lands.integration.claims.ClaimVisualization;
import fr.iban.lands.listeners.*;
import fr.iban.lands.model.land.Land;
import fr.iban.lands.service.LandRepositoryImpl;
import fr.iban.lands.service.LandServiceImpl;
import fr.iban.lands.task.PotionEffectTask;
import fr.iban.lands.utils.Head;
import fr.iban.lands.utils.LandMap;
import fr.iban.lands.utils.SeeClaims;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;

import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class LandsPlugin extends JavaPlugin {

    private static LandsPlugin instance;
    private List<UUID> bypass;
    private List<UUID> debugPlayers;
    private AbstractGuildDataAccess guildDataAccess;
    private Economy econ = null;
    private ExecutorService singleThreadExecutor;
    private FoliaLib foliaLib;

    private LandRepository landRepository;

    private LandService landService;
    private ClaimVisualization claimVisualization = ClaimVisualization.noop();

    private final Map<UUID, SeeClaims> seeClaims = new HashMap<>();
    private LandMap landMap;


    @Override
    public void onEnable() {
        saveDefaultConfig();
        instance = this;
        foliaLib = new FoliaLib(this);
        this.bypass = new ArrayList<>();
        this.debugPlayers = new ArrayList<>();
        this.singleThreadExecutor = Executors.newSingleThreadExecutor();

        landRepository = new LandRepositoryImpl(this);
        landService = new LandServiceImpl(this);

        landMap = new LandMap(landRepository);

        setupEconomy();
        hookGuilds();
        setupClaimVisualization();

        registerCommands();

        registerListeners(
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
                new HeadDatabaseListener(),
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

        if (getServer().getPluginManager().getPlugin("QuickShop-Hikari") != null) {
            getServer().getPluginManager().registerEvents(new ShopListeners(this), this);
            getLogger().info("Intégration QuickShop effectuée.");
        }

        Head.loadAPI();

        PotionEffectTask potionEffectTask = new PotionEffectTask(this);
        getScheduler().runTimer(potionEffectTask, 100, 100);
    }

    @Override
    public void onDisable() {
        shutdownComponents(claimVisualization, singleThreadExecutor, seeClaims.values());
    }

    private void setupClaimVisualization() {
        if (!getConfig().getBoolean("bluemap.enabled", true)
                || !getServer().getPluginManager().isPluginEnabled("BlueMap")) {
            return;
        }

        ClaimVisualization candidate = null;
        try {
            candidate = BlueMapClaimVisualization.create(this);
            claimVisualization = candidate;
            queueInitialClaimVisualizationRebuild();
        } catch (RuntimeException | LinkageError failure) {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            claimVisualization = ClaimVisualization.noop();
            getLogger().log(
                    Level.SEVERE,
                    "BlueMap integration initialization failed; claim visualization is disabled until restart.",
                    failure
            );
            return;
        }
        getLogger().info("Intégration BlueMap effectuée.");
    }

    void queueInitialClaimVisualizationRebuild() {
        runAsyncQueued(claimVisualization::rebuild);
    }

    void shutdownComponents(
            ClaimVisualization visualization,
            ExecutorService executor,
            Collection<SeeClaims> displays
    ) {
        try {
            visualization.close();
        } catch (RuntimeException | LinkageError failure) {
            getLogger().log(Level.SEVERE, "BlueMap integration shutdown failed.", failure);
        }
        executor.shutdown();
        displays.forEach(display -> display.stop());
    }

    private void registerCommands() {
        Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(this)
                .parameterTypes(builder -> builder.addParameterType(Land.class, new LandParameterType(this)))
                .build();

        lamp.register(new LandCommand(this));
        lamp.register(new LandsCommand(this));
        lamp.register(new GuildClaimsCommand(this));
        lamp.register(new MaxClaimsCommand(this));
        lamp.register(new MiscellaneousCommands());
    }

    public void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return;
        }
        econ = rsp.getProvider();
    }

    public Economy getEconomy() {
        return econ;
    }

    private void hookGuilds() {
        if (!getConfig().getBoolean("guild-lands-enabled", false)) {
            return;
        }

        Plugin guildsPlugin = getServer().getPluginManager().getPlugin("MSGuilds");

        if (guildsPlugin == null || !guildsPlugin.isEnabled()) {
            return;
        }

        GuildsDataAccess guildsDataAccess = new GuildsDataAccess(this);
        guildsDataAccess.load();
        if (guildsDataAccess.isEnabled()) {
            this.guildDataAccess = guildsDataAccess;
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
            bypass.add(uuid);
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
            debugPlayers.add(uuid);
        } else {
            debugPlayers.remove(uuid);
        }
    }

    public AbstractGuildDataAccess getGuildDataAccess() {
        return guildDataAccess;
    }

    public String getServerName() {
        return CoreBukkitPlugin.getInstance().getServerName();
    }

    public void runAsyncQueued(Runnable runnable) {
        singleThreadExecutor.execute(runnable);
    }

    public LandRepository getLandRepository() {
        return landRepository;
    }

    public LandService getLandService() {
        return landService;
    }

    public ClaimVisualization getClaimVisualization() {
        return claimVisualization;
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
