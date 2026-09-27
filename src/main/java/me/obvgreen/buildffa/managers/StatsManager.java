package me.obvgreen.buildffa.managers;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StatsManager {

    private final BuildFFAPlugin plugin; // Changed to specific class
    private Connection connection;

    // In-memory stats cache
    private final Map<UUID, PlayerStats> statsCache = new ConcurrentHashMap<>();
    // UUIDs that need flushing to DB
    private final Set<UUID> dirtyPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Leaderboard caches (computed async)
    private volatile List<Map.Entry<UUID, Integer>> topKills = new ArrayList<>();
    private volatile List<Map.Entry<UUID, Integer>> topDeaths = new ArrayList<>();
    private volatile List<Map.Entry<UUID, Integer>> topMaxStreak = new ArrayList<>();
    private volatile List<Map.Entry<UUID, Integer>> topDailyKills = new ArrayList<>();
    private volatile List<Map.Entry<UUID, Integer>> topDailyMaxStreak = new ArrayList<>();

    private volatile Map<UUID, Integer> posKills = new ConcurrentHashMap<>();
    private volatile Map<UUID, Integer> posDeaths = new ConcurrentHashMap<>();
    private volatile Map<UUID, Integer> posMaxStreak = new ConcurrentHashMap<>();
    private volatile Map<UUID, Integer> posDailyKills = new ConcurrentHashMap<>();
    private volatile Map<UUID, Integer> posDailyMaxStreak = new ConcurrentHashMap<>();

    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

    // Cached "current highest killstreak" record holder, maintained incrementally
    // instead of being recomputed via a full stream scan on every read.
    private volatile UUID highestKillstreakUuid = null;
    private volatile int highestKillstreakValue = 0;
    private volatile String highestKillstreakPlayerName = "None";

    private long nextUpdateTime = 0L;
    private int flushTaskId = -1;
    private int leaderboardTaskId = -1;

    public StatsManager(BuildFFAPlugin plugin) { // Changed parameter type
        this.plugin = plugin;
        initDatabase();
        migrateYamlToDatabaseIfPresent();
        loadAllStats();
        startFlushTask();
        startLeaderboardTask();
    }

    // ---------------------
    // Database initialization
    // ---------------------

    private void initDatabase() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "buildffastats.db");
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // Enable WAL mode for better performance and concurrency (must be done before auto-commit false)
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }

            connection.setAutoCommit(false);

            try (Statement st = connection.createStatement()) {
                st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS stats (" +
                                "uuid TEXT PRIMARY KEY," +
                                "kills INTEGER NOT NULL DEFAULT 0," +
                                "deaths INTEGER NOT NULL DEFAULT 0," +
                                "killstreak INTEGER NOT NULL DEFAULT 0," +
                                "max_streak INTEGER NOT NULL DEFAULT 0," +
                                "daily_kills INTEGER NOT NULL DEFAULT 0," +
                                "daily_max_streak INTEGER NOT NULL DEFAULT 0," +
                                "last_date TEXT" +
                                ")"
                );
                connection.commit();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize SQLite database for stats!");
            e.printStackTrace();
        }
    }

    // ---------------------
    // YAML -> SQLite migration
    // ---------------------

    private void migrateYamlToDatabaseIfPresent() {
        if (connection == null) return;

        File ymlFile = new File(plugin.getDataFolder(), "buildffastats.yml");
        if (!ymlFile.exists()) {
            return;
        }

        plugin.getLogger().info("Found old buildffastats.yml. Overwriting SQLite stats with YAML data...");

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(ymlFile);

        if (!yaml.isConfigurationSection("stats")) {
            plugin.getLogger().warning("buildffastats.yml does not contain a valid 'stats' section. Migration skipped.");
            return;
        }

        int migrated = 0;

        try (Statement clear = connection.createStatement();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO stats (uuid,kills,deaths,killstreak,max_streak,daily_kills,daily_max_streak,last_date) " +
                             "VALUES (?,?,?,?,?,?,?,?) " +
                             "ON CONFLICT(uuid) DO UPDATE SET " +
                             "kills=excluded.kills," +
                             "deaths=excluded.deaths," +
                             "killstreak=excluded.killstreak," +
                             "max_streak=excluded.max_streak," +
                             "daily_kills=excluded.daily_kills," +
                             "daily_max_streak=excluded.daily_max_streak," +
                             "last_date=excluded.last_date"
             )) {

            // Clear existing DB stats first so YAML fully overwrites database
            clear.executeUpdate("DELETE FROM stats");

            for (String uuidString : yaml.getConfigurationSection("stats").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    String path = "stats." + uuidString;

                    int kills = yaml.getInt(path + ".kills", 0);
                    int deaths = yaml.getInt(path + ".deaths", 0);
                    int killstreak = yaml.getInt(path + ".killstreak", 0);
                    int maxStreak = yaml.getInt(path + ".max_streak", 0);
                    int dailyKills = yaml.getInt(path + ".daily_kills", 0);
                    int dailyMaxStreak = yaml.getInt(path + ".daily_max_streak", 0);
                    String lastDate = yaml.getString(path + ".last_date", LocalDate.now().toString());

                    ps.setString(1, uuid.toString());
                    ps.setInt(2, kills);
                    ps.setInt(3, deaths);
                    ps.setInt(4, killstreak);
                    ps.setInt(5, maxStreak);
                    ps.setInt(6, dailyKills);
                    ps.setInt(7, dailyMaxStreak);
                    ps.setString(8, lastDate);
                    ps.addBatch();

                    migrated++;
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to migrate stats for entry: " + uuidString);
                }
            }

            ps.executeBatch();
            connection.commit();

            plugin.getLogger().info("Successfully migrated " + migrated + " player stats from buildffastats.yml to SQLite.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to migrate buildffastats.yml to SQLite!");
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            e.printStackTrace();
            return;
        }

        File migratedFile = new File(plugin.getDataFolder(), "buildffastats.yml.migrated");
        if (migratedFile.exists()) {
            migratedFile.delete();
        }

        boolean renamed = ymlFile.renameTo(migratedFile);
        if (renamed) {
            plugin.getLogger().info("Renamed buildffastats.yml to buildffastats.yml.migrated after successful migration.");
        } else {
            plugin.getLogger().warning("Could not rename buildffastats.yml after migration. Please rename or remove it manually to avoid re-importing on next restart.");
        }
    }

    private void loadAllStats() {
        statsCache.clear();

        if (connection == null) return;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM stats")) {

            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                PlayerStats stats = new PlayerStats();
                stats.kills = rs.getInt("kills");
                stats.deaths = rs.getInt("deaths");
                stats.killstreak = rs.getInt("killstreak");
                stats.maxStreak = rs.getInt("max_streak");
                stats.dailyKills = rs.getInt("daily_kills");
                stats.dailyMaxStreak = rs.getInt("daily_max_streak");
                stats.lastDate = rs.getString("last_date");
                statsCache.put(uuid, stats);
            }

            plugin.getLogger().info("Loaded " + statsCache.size() + " player stats into memory.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load stats from SQLite!");
            e.printStackTrace();
        }

        // Compute the initial "highest current killstreak" once at startup.
        // After this, the cache is maintained incrementally (see updateHighestKillstreakCache).
        recomputeHighestKillstreak();
    }

    // ---------------------
    // Periodic flush task
    // ---------------------

    private void startFlushTask() {
        // Every 30 minutes flush dirty stats async with staggered writes
        flushTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () ->
                Bukkit.getScheduler().runTaskAsynchronously(plugin, this::flushDirtyPlayers), 20L * 30 * 60, 20L * 30 * 60);
    }

    private synchronized void flushDirtyPlayers() {
        if (connection == null) return;
        if (dirtyPlayers.isEmpty()) return;

        Set<UUID> toSave = new HashSet<>(dirtyPlayers);
        dirtyPlayers.removeAll(toSave);

        // Stagger writes to avoid overwhelming the disk
        // Process in batches of 10 with 100ms delay between batches
        List<UUID> uuidList = new ArrayList<>(toSave);
        int batchSize = 10;
        int totalBatches = (uuidList.size() + batchSize - 1) / batchSize;

        for (int batch = 0; batch < totalBatches; batch++) {
            int fromIndex = batch * batchSize;
            int toIndex = Math.min(fromIndex + batchSize, uuidList.size());
            List<UUID> batchUuids = uuidList.subList(fromIndex, toIndex);

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO stats (uuid,kills,deaths,killstreak,max_streak,daily_kills,daily_max_streak,last_date) " +
                            "VALUES (?,?,?,?,?,?,?,?) " +
                            "ON CONFLICT(uuid) DO UPDATE SET " +
                            "kills=excluded.kills," +
                            "deaths=excluded.deaths," +
                            "killstreak=excluded.killstreak," +
                            "max_streak=excluded.max_streak," +
                            "daily_kills=excluded.daily_kills," +
                            "daily_max_streak=excluded.daily_max_streak," +
                            "last_date=excluded.last_date"
            )) {
                for (UUID uuid : batchUuids) {
                    PlayerStats s = statsCache.get(uuid);
                    if (s == null) continue;

                    ps.setString(1, uuid.toString());
                    ps.setInt(2, s.kills);
                    ps.setInt(3, s.deaths);
                    ps.setInt(4, s.killstreak);
                    ps.setInt(5, s.maxStreak);
                    ps.setInt(6, s.dailyKills);
                    ps.setInt(7, s.dailyMaxStreak);
                    ps.setString(8, s.lastDate);
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to flush stats to SQLite!");
                e.printStackTrace();
            }

            // Add delay between batches to avoid overwhelming disk
            if (batch < totalBatches - 1) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ---------------------
    // Leaderboard task
    // ---------------------

    private void startLeaderboardTask() {
        // Every 10 minutes recompute leaderboards from in-memory cache
        leaderboardTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () ->
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    updateLeaderboards();
                    nextUpdateTime = System.currentTimeMillis() + (10 * 60 * 1000L);
                }), 0L, 20L * 60 * 10L);
    }

    private void updateLeaderboards() {
        Map<UUID, Integer> mapKills = new HashMap<>();
        Map<UUID, Integer> mapDeaths = new HashMap<>();
        Map<UUID, Integer> mapMaxStreak = new HashMap<>();
        Map<UUID, Integer> mapDailyKills = new HashMap<>();
        Map<UUID, Integer> mapDailyMaxStreak = new HashMap<>();

        String today = LocalDate.now().toString();

        for (Map.Entry<UUID, PlayerStats> entry : statsCache.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerStats s = entry.getValue();

            if (!nameCache.containsKey(uuid)) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                nameCache.put(uuid, op.getName() != null ? op.getName() : "Unknown");
            }

            mapKills.put(uuid, s.kills);
            mapDeaths.put(uuid, s.deaths);
            mapMaxStreak.put(uuid, s.maxStreak);

            if (today.equals(s.lastDate)) {
                mapDailyKills.put(uuid, s.dailyKills);
                mapDailyMaxStreak.put(uuid, s.dailyMaxStreak);
            }
        }

        topKills = sortMapDescending(mapKills);
        topDeaths = sortMapDescending(mapDeaths);
        topMaxStreak = sortMapDescending(mapMaxStreak);
        topDailyKills = sortMapDescending(mapDailyKills);
        topDailyMaxStreak = sortMapDescending(mapDailyMaxStreak);

        posKills = mapPositions(topKills);
        posDeaths = mapPositions(topDeaths);
        posMaxStreak = mapPositions(topMaxStreak);
        posDailyKills = mapPositions(topDailyKills);
        posDailyMaxStreak = mapPositions(topDailyMaxStreak);
    }

    private List<Map.Entry<UUID, Integer>> sortMapDescending(Map<UUID, Integer> map) {
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
        return list;
    }

    private Map<UUID, Integer> mapPositions(List<Map.Entry<UUID, Integer>> sortedList) {
        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < sortedList.size(); i++) {
            positions.put(sortedList.get(i).getKey(), i + 1);
        }
        return positions;
    }

    // ---------------------
    // Highest-current-killstreak cache
    // ---------------------

    /**
     * O(1) incremental update of the cached "highest current killstreak" record holder.
     * Only falls back to a full scan of statsCache in the rare case where the current
     * record holder's own streak just decreased (e.g. they died, or an admin edited it),
     * since we don't track who's in 2nd place.
     */
    private synchronized void updateHighestKillstreakCache(UUID uuid, int newValue) {
        if (uuid.equals(highestKillstreakUuid)) {
            if (newValue >= highestKillstreakValue) {
                highestKillstreakValue = newValue;
                refreshHighestKillstreakName(uuid);
            } else {
                recomputeHighestKillstreak();
            }
        } else if (newValue > highestKillstreakValue) {
            highestKillstreakUuid = uuid;
            highestKillstreakValue = newValue;
            refreshHighestKillstreakName(uuid);
        }
    }

    /**
     * Full O(n) rescan of statsCache to find the current record holder.
     * Only called on startup, when the current leader's own streak drops,
     * or when the current leader's stats are wiped.
     */
    private void recomputeHighestKillstreak() {
        UUID bestUuid = null;
        int bestValue = 0;

        for (Map.Entry<UUID, PlayerStats> entry : statsCache.entrySet()) {
            int streak = entry.getValue().killstreak;
            if (streak > bestValue) {
                bestValue = streak;
                bestUuid = entry.getKey();
            }
        }

        highestKillstreakUuid = bestUuid;
        highestKillstreakValue = bestValue;

        if (bestUuid != null && bestValue > 0) {
            refreshHighestKillstreakName(bestUuid);
        } else {
            highestKillstreakPlayerName = "None";
        }
    }

    /**
     * Resolves a display name without ever risking a blocking Bukkit.getOfflinePlayer()
     * call on the main thread: prefers the live Player object (cheap, in-memory), then
     * falls back to the already-populated nameCache.
     */
    private void refreshHighestKillstreakName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        highestKillstreakPlayerName = (online != null) ? online.getName() : nameCache.getOrDefault(uuid, "Unknown");
    }

    // ---------------------
    // Internal helpers
    // ---------------------

    private PlayerStats getOrCreate(UUID uuid) {
        return statsCache.computeIfAbsent(uuid, id -> {
            PlayerStats s = new PlayerStats();
            s.lastDate = LocalDate.now().toString();
            return s;
        });
    }

    private void markDirty(UUID uuid) {
        dirtyPlayers.add(uuid);
    }

    private void checkDailyReset(UUID uuid) {
        PlayerStats s = getOrCreate(uuid);
        String today = LocalDate.now().toString();
        if (s.lastDate == null || !s.lastDate.equals(today)) {
            s.dailyKills = 0;
            s.dailyMaxStreak = 0;
            s.lastDate = today;
            markDirty(uuid);
        }
    }

    // ---------------------
    // Announcement Logic (NEW)
    // ---------------------

    private void checkMilestoneAnnouncement(UUID killerUuid, int newKillstreak) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("killstreak-announcements.enabled", false)) {
            return;
        }

        List<Integer> milestones = config.getIntegerList("killstreak-announcements.milestones");
        if (milestones.contains(newKillstreak)) {
            Player killer = Bukkit.getPlayer(killerUuid);
            if (killer == null) return; // Player might be offline

            String message = config.getString("killstreak-announcements.reached-milestone-message");
            if (message == null || message.isEmpty()) return;

            message = message.replace("%player%", killer.getName())
                    .replace("%killstreak%", String.valueOf(newKillstreak));

            plugin.broadcastToFFAWorld(message);
        }
    }

    private void checkStreakEndAnnouncement(UUID victimUuid, UUID killerUuid, int oldKillstreak) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("killstreak-announcements.enabled", false)) {
            return;
        }

        int minStreak = config.getInt("killstreak-announcements.minimum-streak-to-announce-end", 5);
        if (oldKillstreak >= minStreak) {
            String message;
            OfflinePlayer victim = Bukkit.getOfflinePlayer(victimUuid);
            if (victim.getName() == null) return;

            if (killerUuid != null) {
                // Killed by another player
                OfflinePlayer killer = Bukkit.getOfflinePlayer(killerUuid);
                if (killer.getName() == null) return;

                message = config.getString("killstreak-announcements.streak-ended-message");
                if (message == null || message.isEmpty()) return;
                message = message.replace("%killer%", killer.getName());
            } else {
                // Environmental death or other reasons
                message = config.getString("killstreak-announcements.streak-ended-no-killer-message");
                if (message == null || message.isEmpty()) return;
            }

            message = message.replace("%victim%", victim.getName())
                    .replace("%killstreak%", String.valueOf(oldKillstreak));

            plugin.broadcastToFFAWorld(message);
        }
    }

    // ---------------------
    // Public API
    // ---------------------

    public int getKills(UUID uuid) { return getOrCreate(uuid).kills; }
    public int getDailyKills(UUID uuid) { checkDailyReset(uuid); return getOrCreate(uuid).dailyKills; }
    public int getDeaths(UUID uuid) { return getOrCreate(uuid).deaths; }
    public int getKillstreak(UUID uuid) { return getOrCreate(uuid).killstreak; }
    public int getMaxKillstreak(UUID uuid) { return getOrCreate(uuid).maxStreak; }
    public int getHighestCurrentKillstreak() { return highestKillstreakValue; }
    public String getHighestCurrentKillstreakPlayer() { return highestKillstreakValue > 0 ? highestKillstreakPlayerName : "None"; }

    public void addKill(UUID uuid) {
        checkDailyReset(uuid);
        PlayerStats s = getOrCreate(uuid);
        s.kills++;
        s.dailyKills++;
        markDirty(uuid);
    }

    public void addDeath(UUID uuid) {
        PlayerStats s = getOrCreate(uuid);
        s.deaths++;
        markDirty(uuid);
    }

    public void addKillstreak(UUID uuid) {
        checkDailyReset(uuid);
        PlayerStats s = getOrCreate(uuid);
        s.killstreak++;

        if (s.killstreak > s.maxStreak) {
            s.maxStreak = s.killstreak;
        }
        if (s.killstreak > s.dailyMaxStreak) {
            s.dailyMaxStreak = s.killstreak;
        }
        markDirty(uuid);
        updateHighestKillstreakCache(uuid, s.killstreak);
        checkMilestoneAnnouncement(uuid, s.killstreak); // Announcement logic call
    }

    /**
     * Resets a player's killstreak and announces it if it was significant.
     * @param victimUuid The UUID of the player whose streak is ending.
     * @param killerUuid The UUID of the player who ended the streak (can be null).
     */
    public void resetKillstreak(UUID victimUuid, UUID killerUuid) {
        PlayerStats s = getOrCreate(victimUuid);
        checkStreakEndAnnouncement(victimUuid, killerUuid, s.killstreak); // Announcement logic call
        s.killstreak = 0;
        markDirty(victimUuid);
        updateHighestKillstreakCache(victimUuid, 0);
    }

    /**
     * Overloaded method for backward compatibility. Resets a player's killstreak without a killer.
     * @param victimUuid The UUID of the player whose streak is ending.
     */
    public void resetKillstreak(UUID victimUuid) {
        resetKillstreak(victimUuid, null);
    }

    // ADMIN METHODS
    public void wipeStats(UUID uuid) {
        statsCache.remove(uuid);
        dirtyPlayers.remove(uuid);

        if (uuid.equals(highestKillstreakUuid)) {
            recomputeHighestKillstreak();
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (connection == null) return;
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM stats WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                    connection.commit();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Failed to wipe stats for " + uuid);
                    e.printStackTrace();
                }
            }
        });
    }
    public void addKills(UUID uuid, int amount) { checkDailyReset(uuid); PlayerStats s = getOrCreate(uuid); s.kills += amount; s.dailyKills += amount; if (s.dailyKills < 0) s.dailyKills = 0; if (s.kills < 0) s.kills = 0; markDirty(uuid); }
    public void addDeaths(UUID uuid, int amount) { PlayerStats s = getOrCreate(uuid); s.deaths += amount; if (s.deaths < 0) s.deaths = 0; markDirty(uuid); }
    public void addCurrentKillstreak(UUID uuid, int amount) { PlayerStats s = getOrCreate(uuid); s.killstreak += amount; if (s.killstreak < 0) s.killstreak = 0; markDirty(uuid); updateHighestKillstreakCache(uuid, s.killstreak); }
    public void addMaxKillstreak(UUID uuid, int amount) { PlayerStats s = getOrCreate(uuid); s.maxStreak += amount; if (s.maxStreak < 0) s.maxStreak = 0; markDirty(uuid); }

    // LEADERBOARD GETTERS
    public String getLeaderboardName(String type, int position) { List<Map.Entry<UUID, Integer>> list = getListByType(type); if (list != null && position >= 1 && position <= list.size()) { UUID uuid = list.get(position - 1).getKey(); return nameCache.getOrDefault(uuid, "Unknown"); } return "None"; }
    public int getLeaderboardValue(String type, int position) { List<Map.Entry<UUID, Integer>> list = getListByType(type); if (list != null && position >= 1 && position <= list.size()) { return list.get(position - 1).getValue(); } return 0; }
    public int getPlayerPosition(UUID uuid, String type) { Map<UUID, Integer> posMap = getPosMapByType(type); return posMap != null ? posMap.getOrDefault(uuid, 0) : 0; }
    private List<Map.Entry<UUID, Integer>> getListByType(String type) { switch (type.toLowerCase()) { case "kills": return topKills; case "deaths": return topDeaths; case "highest_killstreak": return topMaxStreak; case "daily_kills": return topDailyKills; case "daily_highest_killstreak": return topDailyMaxStreak; default: return null; } }
    private Map<UUID, Integer> getPosMapByType(String type) { switch (type.toLowerCase()) { case "kills": return posKills; case "deaths": return posDeaths; case "highest_killstreak": return posMaxStreak; case "daily_kills": return posDailyKills; case "daily_highest_killstreak": return posDailyMaxStreak; default: return null; } }
    public String getRemainingTimeFormatted() { long diff = nextUpdateTime - System.currentTimeMillis(); if (diff <= 0) return "00:00"; long seconds = diff / 1000; long minutes = seconds / 60; seconds = seconds % 60; return String.format("%02d:%02d", minutes, seconds); }

    // SHUTDOWN
    public void shutdown() { if (flushTaskId != -1) { Bukkit.getScheduler().cancelTask(flushTaskId); } if (leaderboardTaskId != -1) { Bukkit.getScheduler().cancelTask(leaderboardTaskId); } flushDirtyPlayers(); if (connection != null) { try { connection.close(); } catch (SQLException ignored) { } } }

    private static class PlayerStats { int kills; int deaths; int killstreak; int maxStreak; int dailyKills; int dailyMaxStreak; String lastDate; }
}