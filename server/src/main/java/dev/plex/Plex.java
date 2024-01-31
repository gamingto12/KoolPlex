package dev.plex;

import dev.plex.cache.DataUtils;
import dev.plex.cache.PlayerCache;
import dev.plex.config.Config;
import dev.plex.handlers.CommandHandler;
import dev.plex.handlers.ListenerHandler;
import dev.plex.hook.CoreProtectHook;
import dev.plex.hook.PrismHook;
import dev.plex.module.ModuleManager;
import dev.plex.player.PlexPlayer;
import dev.plex.punishment.PunishmentManager;
import dev.plex.services.ServiceManager;
import dev.plex.storage.RedisConnection;
import dev.plex.storage.SQLConnection;
import dev.plex.storage.StorageType;
import dev.plex.storage.player.SQLPlayerData;
import dev.plex.storage.punishment.SQLNotes;
import dev.plex.storage.punishment.SQLPunishment;
import dev.plex.util.BuildInfo;
import dev.plex.util.BungeeUtil;
import dev.plex.util.PlexLog;
import dev.plex.util.PlexUtils;
import dev.plex.util.UpdateChecker;
import dev.plex.util.redis.MessageUtil;
import dev.plex.world.CustomWorld;
import java.io.File;
import lombok.Getter;
import lombok.Setter;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
@Setter
public class Plex extends JavaPlugin
{
    public static final BuildInfo build = new BuildInfo();
    private static Plex plugin;
    public Config config;
    public Config messages;
    public Config indefBans;
    public Config commands;
    public Config toggles;
    public File modulesFolder;
    private StorageType storageType = StorageType.SQLITE;
    private SQLConnection sqlConnection;
    private RedisConnection redisConnection;

    private PlayerCache playerCache;
    private SQLPlayerData sqlPlayerData;

    private SQLPunishment sqlPunishment;
    private SQLNotes sqlNotes;

    private ModuleManager moduleManager;
    private ServiceManager serviceManager;
    private PunishmentManager punishmentManager;
    private UpdateChecker updateChecker;

    private Permission permissions;
    private Chat chat;

    private CoreProtectHook coreProtectHook;
    private PrismHook prismHook;

    public static Plex get()
    {
        return plugin;
    }

    @Override
    public void onLoad()
    {
        super.onLoad();
        plugin = this;
        config = new Config(this, "config.yml");
        messages = new Config(this, "messages.yml");
        indefBans = new Config(this, "indefbans.yml");
        commands = new Config(this, "commands.yml");
        toggles = new Config(this, "toggles.yml");
        build.load(this);

        modulesFolder = new File(this.getDataFolder() + File.separator + "modules");
        if (!modulesFolder.exists())
        {
            modulesFolder.mkdir();
        }

        moduleManager = new ModuleManager();
        moduleManager.loadAllModules();
        moduleManager.loadModules();

        //this.setChatHandler(new ChatListener.ChatHandlerImpl());
    }

    @Override
    public void onEnable()
    {
        config.load();
        messages.load();
        toggles.load();

        // Don't add default entries to these files
        indefBans.load(false);
        commands.load(false);

        sqlConnection = new SQLConnection();
//        mongoConnection = new MongoConnection();
        redisConnection = new RedisConnection();

        playerCache = new PlayerCache();

        PlexLog.log("Attempting to connect to DB: {0}", plugin.config.getString("data.central.db"));
        try
        {
            PlexUtils.testConnections();
            PlexLog.log("Connected to " + storageType.name().toUpperCase());
        }
        catch (Exception e)
        {
            PlexLog.error("Failed to connect to " + storageType.name().toUpperCase());
            e.printStackTrace();
        }

        if (!getServer().getPluginManager().isPluginEnabled("Vault"))
        {
            throw new RuntimeException("Vault is required to run on the server alongside a permissions plugin, we recommend LuckPerms!");
        }

        permissions = setupPermissions();
        chat = setupChat();

        if (plugin.getServer().getPluginManager().isPluginEnabled("CoreProtect"))
        {
            PlexLog.log("Hooked into CoreProtect!");
            coreProtectHook = new CoreProtectHook(this);
        }
        else
        {
            PlexLog.debug("Not hooking into CoreProtect");
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("Prism"))
        {
            PlexLog.log("Hooked into Prism!");
            prismHook = new PrismHook(this);
        }
        else
        {
            PlexLog.debug("Not hooking into Prism");
        }

        updateChecker = new UpdateChecker();
        PlexLog.log("Update checking enabled");

        // https://bstats.org/plugin/bukkit/Plex/14143
        Metrics metrics = new Metrics(this, 14143);
        PlexLog.log("Enabled Metrics");

        if (redisConnection != null && redisConnection.isEnabled())
        {
            redisConnection.getJedis();
            PlexLog.log("Connected to Redis!");
            MessageUtil.subscribe();

        }
        else
        {
            PlexLog.log("Redis is disabled in the configuration file, not connecting.");
        }

        sqlPlayerData = new SQLPlayerData();
        sqlPunishment = new SQLPunishment();
        sqlNotes = new SQLNotes();

        new ListenerHandler();
        new CommandHandler();

        punishmentManager = new PunishmentManager();
        punishmentManager.mergeIndefiniteBans();
        PlexLog.log("Punishment System initialized");

        if (!PlexUtils.isFolia())
        {
            // World generation is not supported on Folia yet
            generateWorlds();
        }

        serviceManager = new ServiceManager();
        PlexLog.log("Service Manager initialized");
        serviceManager.startServices();
        PlexLog.log("Started " + serviceManager.serviceCount() + " services.");

        reloadPlayers();
        PlexLog.debug("Registered Bukkit -> BungeeCord Plugin Messaging Channel");
        PlexLog.debug("Velocity Support? " + BungeeUtil.isVelocity());
        PlexLog.debug("BungeeCord Support? " + BungeeUtil.isBungeeCord());
        if (BungeeUtil.isBungeeCord() && BungeeUtil.isVelocity())
        {
            PlexLog.warn("It seems you have both velocity and bungeecord configuration options enabled! When running Velocity, you do NOT need to enable bungeecord.");
        }
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        moduleManager.enableModules();
    }

    @Override
    public void onDisable()
    {
        Bukkit.getOnlinePlayers().forEach(player ->
        {
            PlexPlayer plexPlayer = playerCache.getPlexPlayerMap().get(player.getUniqueId()); //get the player because it's literally impossible for them to not have an object
            sqlPlayerData.update(plexPlayer);
        });
        if (redisConnection != null && redisConnection.isEnabled() && redisConnection.getJedis().isConnected())
        {
            PlexLog.log("Disabling Redis/Jedis. No memory leaks in this Anarchy server!");
            redisConnection.getJedis().close();
        }

        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);

        moduleManager.disableModules();
    }

    private void generateWorlds()
    {
        PlexLog.log("Generating any worlds if needed...");
        for (String key : config.getConfigurationSection("worlds").getKeys(false))
        {
            CustomWorld.generateConfigFlatWorld(key);
        }
        PlexLog.log("Finished with world generation!");
    }

    private void reloadPlayers()
    {
        Bukkit.getOnlinePlayers().forEach(player ->
        {
            PlexPlayer plexPlayer = DataUtils.getPlayer(player.getUniqueId());
            playerCache.getPlexPlayerMap().put(player.getUniqueId(), plexPlayer); //put them into the cache
        });
    }

    private Permission setupPermissions()
    {
        RegisteredServiceProvider<Permission> rsp = Bukkit.getServicesManager().getRegistration(Permission.class);
        permissions = rsp.getProvider();
        return permissions;
    }

    private Chat setupChat()
    {
        RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
        chat = rsp.getProvider();
        return chat;
    }
}
