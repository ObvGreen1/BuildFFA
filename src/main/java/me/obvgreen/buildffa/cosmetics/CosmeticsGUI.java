package me.obvgreen.buildffa.cosmetics;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class CosmeticsGUI implements Listener {

    private final BuildFFAPlugin plugin;
    private static final String MAIN_GUI_TITLE = ChatColor.DARK_AQUA + "Cosmetics Categories";
    private static final String MESSAGES_GUI_TITLE = ChatColor.DARK_AQUA + "Select Kill Message";
    private static final String EFFECTS_GUI_TITLE = ChatColor.DARK_AQUA + "Select Kill Effect";
    private static final String CRIES_GUI_TITLE = ChatColor.DARK_AQUA + "Select Death Cry";

    public CosmeticsGUI(BuildFFAPlugin plugin) {
        this.plugin = plugin;
    }

    public static void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MAIN_GUI_TITLE);

        ItemStack killMessages = new ItemStack(Material.PAPER);
        ItemMeta kmMeta = killMessages.getItemMeta();
        kmMeta.setDisplayName(ChatColor.YELLOW + "Kill Messages");
        killMessages.setItemMeta(kmMeta);

        ItemStack killEffects = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta keMeta = killEffects.getItemMeta();
        keMeta.setDisplayName(ChatColor.RED + "Kill Effects");
        killEffects.setItemMeta(keMeta);

        ItemStack deathCries = new ItemStack(Material.GHAST_TEAR);
        ItemMeta dcMeta = deathCries.getItemMeta();
        dcMeta.setDisplayName(ChatColor.AQUA + "Death Cries");
        deathCries.setItemMeta(dcMeta);

        inv.setItem(11, killMessages);
        inv.setItem(13, killEffects);
        inv.setItem(15, deathCries);

        player.openInventory(inv);
    }

    public static void openKillMessagesGUI(Player player, BuildFFAPlugin plugin) {
        openSelectionGUI(player, plugin, "kill-messages", MESSAGES_GUI_TITLE, plugin.getCosmeticsManager().getPlayerKillMessagePack(player));
    }

    public static void openKillEffectsGUI(Player player, BuildFFAPlugin plugin) {
        openSelectionGUI(player, plugin, "kill-effects", EFFECTS_GUI_TITLE, plugin.getCosmeticsManager().getPlayerKillEffect(player));
    }

    public static void openDeathCriesGUI(Player player, BuildFFAPlugin plugin) {
        openSelectionGUI(player, plugin, "death-cries", CRIES_GUI_TITLE, plugin.getCosmeticsManager().getPlayerDeathCry(player));
    }

    private static void openSelectionGUI(Player player, BuildFFAPlugin plugin, String configKey, String title, String currentSelected) {
        Inventory inv = Bukkit.createInventory(null, 54, title); // Increased to 54 for 27 items
        ConfigurationSection section = plugin.getCosmeticsConfig().getConfigurationSection(configKey);

        if (section != null) {
            int slot = 0;
            for (String id : section.getKeys(false)) {
                if (slot >= 54) break;

                ConfigurationSection itemConfig = section.getConfigurationSection(id);
                String name = ChatColor.translateAlternateColorCodes('&', itemConfig.getString("display-name", "&7" + id));
                String materialStr = itemConfig.getString("material", "PAPER");
                String permission = itemConfig.getString("permission", "");

                ItemStack item = parseMaterial(materialStr);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(name);

                List<String> lore = new ArrayList<>();
                if (configKey.equals("kill-messages")) {
                    lore.add(ChatColor.GRAY + "Example: " + ChatColor.translateAlternateColorCodes('&', itemConfig.getString("normal_kill", "").replace("%killer%", player.getName()).replace("%victim%", "Player")));
                }
                lore.add("");

                if (!permission.isEmpty() && !player.hasPermission(permission)) {
                    item.setType(Material.BARRIER); // Changes material to barrier
                    lore.add(ChatColor.RED + "You do not have permission for this!");
                } else if (id.equalsIgnoreCase(currentSelected)) {
                    lore.add(ChatColor.GREEN + "▶ Currently Selected ◀");
                } else {
                    lore.add(ChatColor.YELLOW + "Click to select!");
                }

                meta.setLore(lore);
                item.setItemMeta(meta);
                inv.setItem(slot, item);
                slot++;
            }
        }
        player.openInventory(inv);
    }

    @SuppressWarnings("deprecation")
    private static ItemStack parseMaterial(String string) {
        if (string == null || string.isEmpty()) return new ItemStack(Material.PAPER);
        String[] parts = string.split(":");
        Material mat;

        try {
            if (parts[0].matches("\\d+")) {
                mat = Material.getMaterial(Integer.parseInt(parts[0]));
            } else {
                mat = Material.matchMaterial(parts[0]);
            }
        } catch (Exception e) {
            mat = Material.PAPER;
        }

        if (mat == null) mat = Material.PAPER;
        short data = 0;
        if (parts.length > 1) {
            try { data = Short.parseShort(parts[1]); } catch (NumberFormatException ignored) {}
        }

        return new ItemStack(mat, 1, data);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        if (title.equals(MAIN_GUI_TITLE)) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            if (event.getSlot() == 11) openKillMessagesGUI(player, plugin);
            else if (event.getSlot() == 13) openKillEffectsGUI(player, plugin);
            else if (event.getSlot() == 15) openDeathCriesGUI(player, plugin);
            return;
        }

        if (title.equals(MESSAGES_GUI_TITLE) || title.equals(EFFECTS_GUI_TITLE) || title.equals(CRIES_GUI_TITLE)) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) return;

            String configKey = title.equals(MESSAGES_GUI_TITLE) ? "kill-messages" : title.equals(EFFECTS_GUI_TITLE) ? "kill-effects" : "death-cries";
            ConfigurationSection section = plugin.getCosmeticsConfig().getConfigurationSection(configKey);

            if (section != null) {
                int currentIndex = 0;
                for (String id : section.getKeys(false)) {
                    if (currentIndex == event.getSlot()) {
                        ConfigurationSection itemConfig = section.getConfigurationSection(id);
                        String permission = itemConfig.getString("permission", "");

                        if (!permission.isEmpty() && !player.hasPermission(permission)) {
                            player.sendMessage(ChatColor.RED + "You do not have permission to use this cosmetic!");
                            player.closeInventory();
                            return;
                        }

                        if (configKey.equals("kill-messages")) {
                            plugin.getCosmeticsManager().setPlayerKillMessagePack(player, id);
                            openKillMessagesGUI(player, plugin);
                        } else if (configKey.equals("kill-effects")) {
                            plugin.getCosmeticsManager().setPlayerKillEffect(player, id);
                            openKillEffectsGUI(player, plugin);
                        } else {
                            plugin.getCosmeticsManager().setPlayerDeathCry(player, id);
                            openDeathCriesGUI(player, plugin);
                        }

                        player.sendMessage(ChatColor.GREEN + "Selected: " + ChatColor.translateAlternateColorCodes('&', itemConfig.getString("display-name", id)));
                        return;
                    }
                    currentIndex++;
                }
            }
        }
    }
}
