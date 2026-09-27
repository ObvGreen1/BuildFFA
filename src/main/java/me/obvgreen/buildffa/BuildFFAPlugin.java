package me.obvgreen.buildffa;

import me.obvgreen.buildffa.commands.BuildFFACommand;
import me.obvgreen.buildffa.cosmetics.CosmeticsGUI;
import me.obvgreen.buildffa.cosmetics.CosmeticsManager;
import me.obvgreen.buildffa.listeners.block.BlockListener;
import me.obvgreen.buildffa.listeners.CombatListener;
import me.obvgreen.buildffa.listeners.DropListener;
import me.obvgreen.buildffa.listeners.FireballListener;
import me.obvgreen.buildffa.listeners.SpawnProtectionListener;
import me.obvgreen.buildffa.managers.CombatManager;
import me.obvgreen.buildffa.managers.HotbarManager;
import me.obvgreen.buildffa.managers.RegionManager;
import me.obvgreen.buildffa.managers.StatsManager;
import me.obvgreen.buildffa.placeholders.BuildFFAPlaceholders;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class BuildFFAPlugin extends JavaPlugin {

    private static final String DEFAULT_WORLD = "buildffa";
    private static final String DEFAULT_LOBBY_WORLD = "lobby";

    private StatsManager statsManager;
    private CombatManager combatManager;
    private HotbarManager hotbarManager;
    private CosmeticsManager cosmeticsManager;
    private RegionManager regionManager;
    private BuildFFACommand buildFFACommand;

    private File cosmeticsFile;
    private FileConfiguration cosmeticsConfig;

    private BuildFFAPlaceholders placeholderExpansion;

    @Override
    public void onEnable() {

        saveDefaultConfig();
        createCosmeticsConfig();

        this.statsManager = new StatsManager(this);
        this.hotbarManager = new HotbarManager(this);
        this.cosmeticsManager = new CosmeticsManager(this);
        this.combatManager = new CombatManager(this);
        this.regionManager = new RegionManager(this);

        this.buildFFACommand = new BuildFFACommand(this);
        PluginCommand command = getCommand("buildffa");
        if (command == null) {
            getLogger().severe("The 'buildffa' command is missing from plugin.yml; disabling BuildFFA.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(buildFFACommand);
        command.setTabCompleter(buildFFACommand);
        getServer().getPluginManager().registerEvents(buildFFACommand, this);

        BlockListener blockListener = new BlockListener(this);
        blockListener.registerListeners(this);
        getServer().getPluginManager().registerEvents(new SpawnProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new FireballListener(this), this);
        getServer().getPluginManager().registerEvents(this.hotbarManager, this);
        getServer().getPluginManager().registerEvents(new CosmeticsGUI(this), this);
        getServer().getPluginManager().registerEvents(new DropListener(this), this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholderExpansion = new BuildFFAPlaceholders(this, this.statsManager);
            this.placeholderExpansion.register();
            getLogger().info("Successfully hooked into PlaceholderAPI.");
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholders will not be available.");
        }

        getLogger().info("BuildFFA has been enabled!");
    }

    @Override
    public void onDisable() {

        if (this.placeholderExpansion != null) {
            this.placeholderExpansion.unregister();
            getLogger().info("Successfully unhooked from PlaceholderAPI.");
        }

        if (this.statsManager != null) {
            this.statsManager.shutdown();
        }

        if (this.hotbarManager != null) {
            this.hotbarManager.closeDatabase();
        }

        if (this.regionManager != null) {
            this.regionManager.closeDatabase();
        }

        if (this.cosmeticsManager != null) {
            this.cosmeticsManager.shutdown();
        }

        getLogger().info("BuildFFA has been disabled!");
    }

    private void createCosmeticsConfig() {
        cosmeticsFile = new File(getDataFolder(), "cosmetics.yml");
        if (!cosmeticsFile.exists()) {
            cosmeticsFile.getParentFile().mkdirs();
            saveResource("cosmetics.yml", false);
        }
        cosmeticsConfig = YamlConfiguration.loadConfiguration(cosmeticsFile);
    }

    public void reloadCustomConfigs() {
        reloadConfig();
        if (cosmeticsFile.exists()) {
            cosmeticsConfig = YamlConfiguration.loadConfiguration(cosmeticsFile);
        }
        cosmeticsManager.reloadCosmetics();
    }

    /**
     * @return name of the arena world players are put into by /buildffa join.
     */
    public String getArenaWorldName() {
        return getConfig().getString("settings.world", DEFAULT_WORLD);
    }

    /**
     * @return name of the world players are sent to by /buildffa leave.
     */
    public String getLobbyWorldName() {
        return getConfig().getString("settings.lobby-world", DEFAULT_LOBBY_WORLD);
    }

    /**
     * @return {@code true} if the location is in the configured arena world.
     */
    public boolean isArenaWorld(Location location) {
        return location != null
                && location.getWorld() != null
                && location.getWorld().getName().equalsIgnoreCase(getArenaWorldName());
    }

    /**
     * @return the arena spawn, falling back to the given coordinates when the world is missing.
     */
    public Location getArenaSpawn() {
        return resolveSpawn("settings.spawn", getArenaWorldName(), 0.5D, 147D, -4.5D);
    }

    /**
     * @return the lobby spawn, falling back to the given coordinates when the world is missing.
     */
    public Location getLobbySpawn() {
        return resolveSpawn("settings.lobby-spawn", getLobbyWorldName(), 0.5D, 101D, 0.5D);
    }

    private Location resolveSpawn(String path, String worldName, double defX, double defY, double defZ) {
        World world = Bukkit.getWorld(worldName);
        double x = defX, y = defY, z = defZ;

        String raw = getConfig().getString(path);
        if (raw != null) {
            String[] parts = raw.split(",");
            if (parts.length == 3) {
                try {
                    x = Double.parseDouble(parts[0].trim());
                    y = Double.parseDouble(parts[1].trim());
                    z = Double.parseDouble(parts[2].trim());
                } catch (NumberFormatException ex) {
                    getLogger().warning("Invalid '" + path + "' value in config.yml (\"" + raw
                            + "\"). Expected \"x, y, z\". Falling back to defaults.");
                }
            } else {
                getLogger().warning("Invalid '" + path + "' value in config.yml (\"" + raw
                        + "\"). Expected \"x, y, z\". Falling back to defaults.");
            }
        }

        if (world == null) {
            getLogger().warning("Could not find the '" + worldName + "' world; using coordinates only.");
            return new Location(null, x, y, z);
        }
        return new Location(world, x, y, z);
    }

    public void broadcastToFFAWorld(String message) {
        World ffaWorld = Bukkit.getWorld(getArenaWorldName());
        if (ffaWorld == null) {
            getLogger().warning("Could not find the BuildFFA world '" + getArenaWorldName() + "' to broadcast a message.");
            return;
        }

        String formattedMessage = ChatColor.translateAlternateColorCodes('&', message);

        for (Player player : ffaWorld.getPlayers()) {
            player.sendMessage(formattedMessage);
        }
    }

    public FileConfiguration getCosmeticsConfig() { return cosmeticsConfig; }
    public StatsManager getStatsManager() { return statsManager; }
    public CombatManager getCombatManager() { return combatManager; }
    public HotbarManager getHotbarManager() { return hotbarManager; }
    public RegionManager getRegionManager() { return regionManager; }
    public CosmeticsManager getCosmeticsManager() { return cosmeticsManager; }
    public BuildFFACommand getBuildFFACommand() { return buildFFACommand; }
}