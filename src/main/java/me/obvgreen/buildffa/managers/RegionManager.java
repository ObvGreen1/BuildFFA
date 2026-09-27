package me.obvgreen.buildffa.managers;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class RegionManager {

    private final BuildFFAPlugin plugin;
    private Connection connection;

    private Location pos1;
    private Location pos2;

    public RegionManager(BuildFFAPlugin plugin) {
        this.plugin = plugin;
        initDatabase();
        loadRegion();
    }

    private void initDatabase() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            File dbFile = new File(plugin.getDataFolder(), "regions.db");

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
            }

            connection.setAutoCommit(false);

            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS regions (" +
                        "id INTEGER PRIMARY KEY CHECK (id IN (1, 2))," +
                        "world TEXT NOT NULL," +
                        "x REAL NOT NULL," +
                        "y REAL NOT NULL," +
                        "z REAL NOT NULL)");
                connection.commit();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize SQLite database for spawn protection regions!");
            e.printStackTrace();
        }
    }

    private void loadRegion() {
        if (connection == null) return;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, world, x, y, z FROM regions")) {
            while (rs.next()) {
                World world = Bukkit.getWorld(rs.getString("world"));
                if (world == null) {
                    plugin.getLogger().warning("Spawn protection region world '" + rs.getString("world") + "' is not loaded. Position ignored.");
                    continue;
                }
                Location loc = new Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
                if (rs.getInt("id") == 1) {
                    pos1 = loc;
                } else {
                    pos2 = loc;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load spawn protection region!");
            e.printStackTrace();
        }
    }

    public void setPosition(int pos, Location loc) {
        if (pos != 1 && pos != 2) return;

        loc = loc.clone();
        if (pos == 1) {
            pos1 = loc;
        } else {
            pos2 = loc;
        }

        if (connection == null) return;

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO regions (id, world, x, y, z) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, pos);
            ps.setString(2, loc.getWorld().getName());
            ps.setDouble(3, loc.getX());
            ps.setDouble(4, loc.getY());
            ps.setDouble(5, loc.getZ());
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save spawn protection position " + pos + "!");
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            e.printStackTrace();
        }
    }

    public Location getPosition(int pos) {
        Location loc = pos == 1 ? pos1 : pos2;
        return loc == null ? null : loc.clone();
    }

    public boolean isRegionDefined() {
        return pos1 != null && pos2 != null;
    }

    public boolean isInside(Location loc) {
        if (loc == null || !isRegionDefined()) return false;
        World world = loc.getWorld();
        if (world == null || !world.getName().equalsIgnoreCase(pos1.getWorld().getName())) return false;

        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double maxY = Math.max(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());

        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public void closeDatabase() {
        if (connection == null) return;
        try {
            connection.commit();
            connection.close();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to close spawn protection database!");
            e.printStackTrace();
        } finally {
            connection = null;
        }
    }
}