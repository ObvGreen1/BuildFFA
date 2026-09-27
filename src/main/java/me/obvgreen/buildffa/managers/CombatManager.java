package me.obvgreen.buildffa.managers;

import me.obvgreen.buildffa.BuildFFAPlugin;
import me.obvgreen.buildffa.commands.BuildFFACommand;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatManager {

    private static final String DOUBLE_JUMP_MARKER = ChatColor.BLACK + "double_jump_feather";

    private final BuildFFAPlugin plugin;
    private final Map<UUID, UUID> taggedBy = new HashMap<>();
    private final Map<UUID, Long> tagTime = new HashMap<>();
    private final Map<UUID, EnderPearl> activePearls = new HashMap<>();

    private final Map<String, Material> materialCache = new ConcurrentHashMap<>();
    private final Map<String, PotionEffectType> potionTypeCache = new ConcurrentHashMap<>();

    public CombatManager(BuildFFAPlugin plugin) {
        this.plugin = plugin;
    }

    public void tagPlayer(Player victim, Player attacker) {
        UUID victimId = victim.getUniqueId();
        taggedBy.put(victimId, attacker.getUniqueId());
        tagTime.put(victimId, System.currentTimeMillis());
    }

    public boolean isInCombat(Player player) {
        Long tag = tagTime.get(player.getUniqueId());
        if (tag == null) return false;
        long timeInCombat = (System.currentTimeMillis() - tag) / 1000L;
        int maxTime = plugin.getConfig().getInt("combat_time", 15);
        return timeInCombat <= maxTime;
    }

    public Player getAttacker(Player player) {
        if (!isInCombat(player)) return null;
        UUID attackerId = taggedBy.get(player.getUniqueId());
        return attackerId == null ? null : Bukkit.getPlayer(attackerId);
    }

    public void removeTag(Player player) {
        UUID uuid = player.getUniqueId();
        taggedBy.remove(uuid);
        tagTime.remove(uuid);
    }

    public void addPearl(Player player, EnderPearl pearl) {
        activePearls.put(player.getUniqueId(), pearl);
    }

    public EnderPearl getPearl(Player player) {
        return activePearls.get(player.getUniqueId());
    }

    public void removeActivePearl(Player player) {
        EnderPearl pearl = activePearls.remove(player.getUniqueId());
        if (pearl != null && pearl.isValid()) {
            pearl.remove();
        }
    }

    public boolean isDoubleJumpFeather(ItemStack item) {
        if (item == null || item.getType() != Material.FEATHER) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }

        List<String> lore = meta.getLore();
        return lore != null && lore.contains(DOUBLE_JUMP_MARKER);
    }

    public ItemStack createDoubleJumpFeather(int amount) {
        ItemStack feather = new ItemStack(Material.FEATHER, amount);
        ItemMeta meta = feather.getItemMeta();

        if (meta != null) {
            String displayName = plugin.getConfig().getString("double-jump-feather.display-name", "&fDouble Jump Feather");
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));

            List<String> lore = new ArrayList<>();
            List<String> configuredLore = plugin.getConfig().getStringList("double-jump-feather.lore");
            if (configuredLore != null && !configuredLore.isEmpty()) {
                for (String line : configuredLore) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', line));
                }
            }
            lore.add(DOUBLE_JUMP_MARKER);

            meta.setLore(lore);
            feather.setItemMeta(meta);
        }

        return feather;
    }

    public void checkAndApplyCombatLog(Player victim) {
        if (!isInCombat(victim)) {
            return;
        }

        Player attacker = getAttacker(victim);
        boolean credited = awardKillCredit(victim, attacker);

        String message = credited
                ? plugin.getCosmeticsManager().getKillMessage(attacker, "combat_log", attacker, victim)
                : plugin.getCosmeticsManager().getKillMessage(victim, "normal_death", null, victim);

        broadcastMessage(victim.getWorld(), message);
        removeTag(victim);
    }

    public void handleDeath(Player victim, Player attacker, String deathType) {
        removeActivePearl(victim);

        if (attacker != null) {
            plugin.getCosmeticsManager().playKillEffect(attacker, victim);
        }

        World world = victim.getWorld();
        victim.setHealth(victim.getMaxHealth());
        victim.setFoodLevel(20);
        victim.setFireTicks(0);
        victim.teleport(plugin.getArenaSpawn());

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (victim.isOnline()) {
                plugin.getCosmeticsManager().playDeathCry(victim);
            }
        }, 1L);

        removeTag(victim);

        boolean isVoid = "void".equals(deathType);
        boolean credited = awardKillCredit(victim, attacker);

        String message;
        if (credited) {
            String messageKey = isVoid ? "void_kill" : "combat_log".equals(deathType) ? "combat_log" : "normal_kill";
            message = plugin.getCosmeticsManager().getKillMessage(attacker, messageKey, attacker, victim);
        } else {
            String messageKey = isVoid ? "void_death" : "normal_death";
            message = plugin.getCosmeticsManager().getKillMessage(victim, messageKey, null, victim);
        }

        broadcastMessage(world, message);

        BuildFFACommand commandClass = plugin.getBuildFFACommand();
        if (commandClass != null) {
            commandClass.FFAJoin(victim);
        }
    }

    private boolean awardKillCredit(Player victim, Player attacker) {
        UUID victimId = victim.getUniqueId();
        boolean creditAttacker = attacker != null && attacker.isOnline();
        UUID attackerId = creditAttacker ? attacker.getUniqueId() : null;

        plugin.getStatsManager().addDeath(victimId);
        plugin.getStatsManager().resetKillstreak(victimId, attackerId);

        if (creditAttacker) {
            plugin.getStatsManager().addKill(attackerId);
            plugin.getStatsManager().addKillstreak(attackerId);

            int currentStreak = plugin.getStatsManager().getKillstreak(attackerId);
            attacker.setHealth(attacker.getMaxHealth());
            giveKillRewards(attacker, currentStreak);
        }

        return creditAttacker;
    }

    private void broadcastMessage(World world, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }

        String coloredMessage = ChatColor.translateAlternateColorCodes('&', message);
        for (Player p : world.getPlayers()) {
            p.sendMessage(coloredMessage);
        }
    }

    @SuppressWarnings("deprecation")
    private void giveKillRewards(Player player, int streak) {
        if (!plugin.isArenaWorld(player.getLocation())) {
            return;
        }

        FileConfiguration config = plugin.getConfig();
        int maxConfigured = config.getInt("max-configured-streak", 10);
        String streakKey = String.valueOf(Math.min(streak, maxConfigured));
        String path = "kill-rewards." + streakKey;

        if (!config.contains(path)) {
            return;
        }

        List<String> items = config.getStringList(path + ".items");
        if (items != null && !items.isEmpty()) {
            for (String itemString : items) {
                if (itemString == null || itemString.trim().isEmpty() || itemString.equalsIgnoreCase("null")) {
                    continue;
                }

                String[] parts = itemString.split(":");
                if (parts.length < 2) {
                    continue;
                }

                try {
                    String materialKey = parts[0].toUpperCase().replace("-", "_");
                    int amount = Integer.parseInt(parts[1]);

                    if ("FEATHER".equals(materialKey)) {
                        ItemStack feather = createDoubleJumpFeather(amount);
                        plugin.getHotbarManager().giveItemRespectingHotbar(player, feather, "FEATHER");
                        continue;
                    }

                    Material material = materialCache.computeIfAbsent(materialKey, Material::matchMaterial);
                    if (material == null) {
                        continue;
                    }

                    ItemStack itemToAdd = new ItemStack(material, amount);

                    if (material == Material.GOLDEN_APPLE) {
                        plugin.getHotbarManager().giveItemRespectingHotbar(player, itemToAdd, "GAPPLE");
                    } else if (material == Material.FIREBALL) {
                        plugin.getHotbarManager().giveItemRespectingHotbar(player, itemToAdd, "FIREBALL");
                    } else if (material == Material.ENDER_PEARL) {
                        plugin.getHotbarManager().giveItemRespectingHotbar(player, itemToAdd, "PEARL");
                    } else {
                        player.getInventory().addItem(itemToAdd);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        List<String> potions = config.getStringList(path + ".potions");
        if (potions != null && !potions.isEmpty()) {
            for (String potionString : potions) {
                if (potionString == null || potionString.trim().isEmpty() || potionString.equalsIgnoreCase("null")) {
                    continue;
                }

                try {
                    String[] parts = potionString.split(":");
                    if (parts.length != 3) continue;

                    String potionKey = parts[0].toUpperCase();
                    PotionEffectType type = potionTypeCache.computeIfAbsent(potionKey, PotionEffectType::getByName);
                    if (type == null) continue;

                    int amplifier = Integer.parseInt(parts[1]);
                    int durationTicks = Integer.parseInt(parts[2]) * 20;

                    ItemStack potionItem = new ItemStack(Material.POTION, 1);
                    PotionMeta meta = (PotionMeta) potionItem.getItemMeta();
                    if (meta != null) {
                        meta.addCustomEffect(new PotionEffect(type, durationTicks, amplifier), true);
                        meta.setDisplayName("§b" + parts[0] + " Potion");
                        potionItem.setItemMeta(meta);
                        player.getInventory().addItem(potionItem);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }
}