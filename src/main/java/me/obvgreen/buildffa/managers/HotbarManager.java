package me.obvgreen.buildffa.managers;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HotbarManager implements Listener {

    private final BuildFFAPlugin plugin;
    private Connection connection;

    private static final String GUI_NAME = "§8Hotbar Manager";

    private final Map<String, Integer> defaultSlots = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> slotCache = new HashMap<>();
    private final Map<String, ItemStack> guiTemplates = new HashMap<>();

    // Dirty players tracking for RAM caching
    private final Set<UUID> dirtyPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Cooldown tracking for resetLayout
    private final Map<UUID, Long> resetCooldowns = new ConcurrentHashMap<>();
    private static final long RESET_COOLDOWN_MS = 5000; // 5 seconds
    
    private int flushTaskId = -1;

    public HotbarManager(BuildFFAPlugin plugin) {
        this.plugin = plugin;

        defaultSlots.put("SWORD", 0);
        defaultSlots.put("WOOL", 1);
        defaultSlots.put("AXE", 2);
        defaultSlots.put("PICKAXE", 3);
        defaultSlots.put("SHEARS", 4);
        defaultSlots.put("GAPPLE", 5);
        defaultSlots.put("FIREBALL", 6);
        defaultSlots.put("PEARL", 7);
        defaultSlots.put("FEATHER", 8);

        setupDatabase();
        loadAllHotbars();
        startFlushTask();

        setupGuiTemplates();
    }

    private void setupDatabase() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            File dbFile = new File(plugin.getDataFolder(), "hotbars.db");

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // Enable WAL mode for better performance and concurrency (must be done before auto-commit false)
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
            }

            connection.setAutoCommit(false);

            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS hotbars (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "SWORD INT DEFAULT -1, " +
                        "WOOL INT DEFAULT -1, " +
                        "AXE INT DEFAULT -1, " +
                        "PICKAXE INT DEFAULT -1, " +
                        "SHEARS INT DEFAULT -1, " +
                        "GAPPLE INT DEFAULT -1, " +
                        "FIREBALL INT DEFAULT -1, " +
                        "PEARL INT DEFAULT -1, " +
                        "FEATHER INT DEFAULT -1)");
                connection.commit();
            }

            ensureColumnExists("FEATHER");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to connect to SQLite database!");
            e.printStackTrace();
        }
    }

    private void ensureColumnExists(String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(null, null, "hotbars", columnName)) {
            if (!resultSet.next()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE hotbars ADD COLUMN " + columnName + " INT DEFAULT -1");
                    connection.commit();
                }
            }
        }
    }

    private void loadAllHotbars() {
        if (connection == null) return;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM hotbars")) {

            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                Map<String, Integer> playerLayout = new HashMap<>();
                for (String key : defaultSlots.keySet()) {
                    playerLayout.put(key, rs.getInt(key));
                }
                slotCache.put(uuid, playerLayout);
            }

            plugin.getLogger().info("Loaded " + slotCache.size() + " player hotbars into memory.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load hotbars from SQLite!");
            e.printStackTrace();
        }
    }

    private void startFlushTask() {
        // Every 30 minutes flush dirty hotbars async with staggered writes
        flushTaskId = org.bukkit.Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () ->
                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, this::flushDirtyHotbars), 20L * 30 * 60, 20L * 30 * 60);
    }

    private synchronized void flushDirtyHotbars() {
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
                    "REPLACE INTO hotbars (uuid, SWORD, WOOL, AXE, PICKAXE, SHEARS, GAPPLE, FIREBALL, PEARL, FEATHER) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                for (UUID uuid : batchUuids) {
                    Map<String, Integer> layout = slotCache.get(uuid);
                    if (layout == null) continue;

                    ps.setString(1, uuid.toString());
                    ps.setInt(2, layout.getOrDefault("SWORD", -1));
                    ps.setInt(3, layout.getOrDefault("WOOL", -1));
                    ps.setInt(4, layout.getOrDefault("AXE", -1));
                    ps.setInt(5, layout.getOrDefault("PICKAXE", -1));
                    ps.setInt(6, layout.getOrDefault("SHEARS", -1));
                    ps.setInt(7, layout.getOrDefault("GAPPLE", -1));
                    ps.setInt(8, layout.getOrDefault("FIREBALL", -1));
                    ps.setInt(9, layout.getOrDefault("PEARL", -1));
                    ps.setInt(10, layout.getOrDefault("FEATHER", -1));
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to flush hotbars to SQLite!");
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

    public void closeDatabase() {
        if (flushTaskId != -1) {
            org.bukkit.Bukkit.getScheduler().cancelTask(flushTaskId);
        }
        flushDirtyHotbars(); // Save any pending changes
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void setupGuiTemplates() {
        guiTemplates.put("GLASS", createGuiItem(Material.STAINED_GLASS_PANE, (short) 15, "§8"));
        guiTemplates.put("RESET", createGuiItem(Material.REDSTONE_BLOCK, (short) 0, "§c§lReset Layout"));
        guiTemplates.put("SWORD", createGuiItem(Material.STONE_SWORD, (short) 0, "§aSword"));
        guiTemplates.put("WOOL", createGuiItem(Material.WOOL, (short) 0, "§aBlocks"));
        guiTemplates.put("AXE", createGuiItem(Material.WOOD_AXE, (short) 0, "§aAxe"));
        guiTemplates.put("PICKAXE", createGuiItem(Material.IRON_PICKAXE, (short) 0, "§aPickaxe"));
        guiTemplates.put("SHEARS", createGuiItem(Material.SHEARS, (short) 0, "§aShears"));
        guiTemplates.put("GAPPLE", createGuiItem(Material.GOLDEN_APPLE, (short) 0, "§aGolden Apple"));
        guiTemplates.put("FIREBALL", createGuiItem(Material.FIREBALL, (short) 0, "§aFireball"));
        guiTemplates.put("PEARL", createGuiItem(Material.ENDER_PEARL, (short) 0, "§aEnder Pearl"));
        guiTemplates.put("FEATHER", createGuiItem(Material.FEATHER, (short) 0, "§aDouble Jump Feather"));
    }

    public int getSlot(UUID uuid, String itemKey) {
        if (slotCache.containsKey(uuid) && slotCache.get(uuid).containsKey(itemKey)) {
            return slotCache.get(uuid).get(itemKey);
        }

        // Data should be in cache from loadAllHotbars, but fallback to DB
        int value = defaultSlots.getOrDefault(itemKey, -1);
        slotCache.computeIfAbsent(uuid, k -> new HashMap<>()).put(itemKey, value);
        return value;
    }

    public void giveItemRespectingHotbar(Player player, ItemStack rewardItem, String itemKey) {
        int preferredSlot = getSlot(player.getUniqueId(), itemKey);
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();

        if (preferredSlot >= 0 && preferredSlot <= 8) {
            ItemStack currentItem = inv.getItem(preferredSlot);

            if (currentItem == null || currentItem.getType() == Material.AIR) {
                inv.setItem(preferredSlot, rewardItem);
            } else if (currentItem.isSimilar(rewardItem)) {
                int newAmount = currentItem.getAmount() + rewardItem.getAmount();
                if (newAmount <= currentItem.getMaxStackSize()) {
                    currentItem.setAmount(newAmount);
                } else {
                    int leftover = newAmount - currentItem.getMaxStackSize();
                    currentItem.setAmount(currentItem.getMaxStackSize());
                    rewardItem.setAmount(leftover);
                    inv.addItem(rewardItem);
                }
            } else {
                inv.setItem(preferredSlot, rewardItem);
                inv.addItem(currentItem);
            }
        } else {
            inv.addItem(rewardItem);
        }
    }

    public void openGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 18, GUI_NAME);
        UUID uuid = player.getUniqueId();

        for (int i = 0; i < 9; i++) {
            inv.setItem(i, i == 4 ? guiTemplates.get("RESET").clone() : guiTemplates.get("GLASS").clone());
        }

        setItemIfValid(inv, getSlot(uuid, "SWORD") + 9, guiTemplates.get("SWORD").clone());
        setItemIfValid(inv, getSlot(uuid, "WOOL") + 9, guiTemplates.get("WOOL").clone());
        setItemIfValid(inv, getSlot(uuid, "AXE") + 9, guiTemplates.get("AXE").clone());
        setItemIfValid(inv, getSlot(uuid, "PICKAXE") + 9, guiTemplates.get("PICKAXE").clone());
        setItemIfValid(inv, getSlot(uuid, "SHEARS") + 9, guiTemplates.get("SHEARS").clone());
        setItemIfValid(inv, getSlot(uuid, "GAPPLE") + 9, guiTemplates.get("GAPPLE").clone());
        setItemIfValid(inv, getSlot(uuid, "FIREBALL") + 9, guiTemplates.get("FIREBALL").clone());
        setItemIfValid(inv, getSlot(uuid, "PEARL") + 9, guiTemplates.get("PEARL").clone());
        setItemIfValid(inv, getSlot(uuid, "FEATHER") + 9, guiTemplates.get("FEATHER").clone());

        player.openInventory(inv);
    }

    private void setItemIfValid(Inventory inv, int slot, ItemStack item) {
        if (slot >= 9 && slot <= 17) {
            inv.setItem(slot, item);
        } else {
            for (int i = 9; i <= 17; i++) {
                ItemStack current = inv.getItem(i);
                if (current == null || current.getType() == Material.AIR) {
                    inv.setItem(i, item);
                    break;
                }
            }
        }
    }

    private ItemStack createGuiItem(Material mat, short data, String name) {
        ItemStack item = new ItemStack(mat, 1, data);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    public Map<String, Integer> getLayout(UUID uuid) {
        Map<String, Integer> layout = slotCache.get(uuid);
        if (layout == null) {
            layout = new HashMap<>(defaultSlots);
        }
        return new HashMap<>(layout);
    }

    public void resetLayout(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Check cooldown
        Long lastReset = resetCooldowns.get(uuid);
        long now = System.currentTimeMillis();
        if (lastReset != null && (now - lastReset) < RESET_COOLDOWN_MS) {
            long remaining = (RESET_COOLDOWN_MS - (now - lastReset)) / 1000;
            player.sendMessage("§cPlease wait " + remaining + " seconds before resetting again.");
            return;
        }
        
        resetCooldowns.put(uuid, now);
        slotCache.remove(uuid);
        dirtyPlayers.add(uuid);

        player.sendMessage("§aYour hotbar layout has been reset to default!");
        openGUI(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!GUI_NAME.equals(event.getView().getTitle())) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory().getType() == InventoryType.PLAYER) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot == 4) {
            player.setItemOnCursor(new ItemStack(Material.AIR));
            resetLayout(player);
            return;
        }

        if (slot >= 9 && slot <= 17) {
            InventoryAction action = event.getAction();
            switch (action) {
                case PICKUP_ALL:
                case PICKUP_HALF:
                case PICKUP_ONE:
                case PICKUP_SOME:
                case PLACE_ALL:
                case PLACE_ONE:
                case PLACE_SOME:
                case SWAP_WITH_CURSOR:
                    event.setCancelled(false);
                    break;
                default:
                    break;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!GUI_NAME.equals(event.getView().getTitle())) {
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (slot < 9 || slot > 17) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!GUI_NAME.equals(event.getView().getTitle())) {
            return;
        }

        Player player = (Player) event.getPlayer();
        UUID uuid = player.getUniqueId();
        player.setItemOnCursor(new ItemStack(Material.AIR));

        Map<String, Integer> newLayout = new HashMap<>();

        for (int i = 9; i <= 17; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;

            int hotbarSlot = i - 9;

            switch (item.getType()) {
                case STONE_SWORD: newLayout.put("SWORD", hotbarSlot); break;
                case WOOL: newLayout.put("WOOL", hotbarSlot); break;
                case WOOD_AXE: newLayout.put("AXE", hotbarSlot); break;
                case IRON_PICKAXE: newLayout.put("PICKAXE", hotbarSlot); break;
                case SHEARS: newLayout.put("SHEARS", hotbarSlot); break;
                case GOLDEN_APPLE: newLayout.put("GAPPLE", hotbarSlot); break;
                case FIREBALL: newLayout.put("FIREBALL", hotbarSlot); break;
                case ENDER_PEARL: newLayout.put("PEARL", hotbarSlot); break;
                case FEATHER: newLayout.put("FEATHER", hotbarSlot); break;
                default: break;
            }
        }

        slotCache.put(uuid, newLayout);
        dirtyPlayers.add(uuid); // Mark as dirty for periodic save

        player.sendMessage("§aYour hotbar layout has been saved!");
    }
}
