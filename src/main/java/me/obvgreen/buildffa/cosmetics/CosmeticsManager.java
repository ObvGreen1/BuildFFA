package me.obvgreen.buildffa.cosmetics;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

public class CosmeticsManager {

    private final BuildFFAPlugin plugin;
    private final Map<UUID, String> playerKillMessagePacks = new HashMap<>();
    private final Map<UUID, String> playerKillEffects = new HashMap<>();
    private final Map<UUID, String> playerDeathCries = new HashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;

    // Dirty players tracking for RAM caching
    private final Set<UUID> dirtyPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Cooldown tracking for cosmetic changes
    private final Map<UUID, Long> cosmeticCooldowns = new ConcurrentHashMap<>();
    private static final long COSMETIC_COOLDOWN_MS = 3000; // 3 seconds
    
    private int flushTaskId = -1;

    public CosmeticsManager(BuildFFAPlugin plugin) {
        this.plugin = plugin;
        loadData();
        startFlushTask();
    }

    private void loadData() {
        dataFile = new File(plugin.getDataFolder(), "cosmetics_data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException ignored) {}
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        if (dataConfig.contains("players")) {
            for (String uuidStr : dataConfig.getConfigurationSection("players").getKeys(false)) {
                UUID uuid = UUID.fromString(uuidStr);
                playerKillMessagePacks.put(uuid, dataConfig.getString("players." + uuidStr + ".kill_message", "default"));
                playerKillEffects.put(uuid, dataConfig.getString("players." + uuidStr + ".kill_effect", "none"));
                playerDeathCries.put(uuid, dataConfig.getString("players." + uuidStr + ".death_cry", "none"));
            }
        }
    }

    private void saveData() {
        for (Map.Entry<UUID, String> entry : playerKillMessagePacks.entrySet()) {
            dataConfig.set("players." + entry.getKey().toString() + ".kill_message", entry.getValue());
        }
        for (Map.Entry<UUID, String> entry : playerKillEffects.entrySet()) {
            dataConfig.set("players." + entry.getKey().toString() + ".kill_effect", entry.getValue());
        }
        for (Map.Entry<UUID, String> entry : playerDeathCries.entrySet()) {
            dataConfig.set("players." + entry.getKey().toString() + ".death_cry", entry.getValue());
        }
        try { dataConfig.save(dataFile); } catch (IOException ignored) {}
    }

    private void startFlushTask() {
        // Every 30 minutes flush dirty cosmetics async with staggered writes
        flushTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () ->
                Bukkit.getScheduler().runTaskAsynchronously(plugin, this::flushDirtyCosmetics), 20L * 30 * 60, 20L * 30 * 60);
    }

    private synchronized void flushDirtyCosmetics() {
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

            // Update config for this batch
            for (UUID uuid : batchUuids) {
                if (playerKillMessagePacks.containsKey(uuid)) {
                    dataConfig.set("players." + uuid.toString() + ".kill_message", playerKillMessagePacks.get(uuid));
                }
                if (playerKillEffects.containsKey(uuid)) {
                    dataConfig.set("players." + uuid.toString() + ".kill_effect", playerKillEffects.get(uuid));
                }
                if (playerDeathCries.containsKey(uuid)) {
                    dataConfig.set("players." + uuid.toString() + ".death_cry", playerDeathCries.get(uuid));
                }
            }

            try { dataConfig.save(dataFile); } catch (IOException e) {
                plugin.getLogger().severe("Failed to flush cosmetics to file!");
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

    public void shutdown() {
        if (flushTaskId != -1) {
            Bukkit.getScheduler().cancelTask(flushTaskId);
        }
        flushDirtyCosmetics(); // Save any pending changes
    }

    public void reloadCosmetics() {
        loadData();
    }

    // --- Kill Messages ---
    public String getPlayerKillMessagePack(Player player) {
        return playerKillMessagePacks.getOrDefault(player.getUniqueId(), "default");
    }

    public void setPlayerKillMessagePack(Player player, String packId) {
        UUID uuid = player.getUniqueId();
        
        // Check cooldown
        Long lastChange = cosmeticCooldowns.get(uuid);
        long now = System.currentTimeMillis();
        if (lastChange != null && (now - lastChange) < COSMETIC_COOLDOWN_MS) {
            long remaining = (COSMETIC_COOLDOWN_MS - (now - lastChange)) / 1000;
            player.sendMessage("§cPlease wait " + remaining + " seconds before changing cosmetics again.");
            return;
        }
        
        cosmeticCooldowns.put(uuid, now);
        playerKillMessagePacks.put(uuid, packId);
        dirtyPlayers.add(uuid);
    }

    public String getKillMessage(Player owner, String messageType, Player killer, Player victim) {
        String packId = getPlayerKillMessagePack(owner);
        ConfigurationSection packSection = plugin.getCosmeticsConfig().getConfigurationSection("kill-messages." + packId);

        if (packSection == null) packSection = plugin.getCosmeticsConfig().getConfigurationSection("kill-messages.default");
        if (packSection == null) return ChatColor.RED + victim.getName() + " died.";

        String rawMessage = packSection.getString(messageType, "&c%victim% died.");
        if (killer != null) rawMessage = rawMessage.replace("%killer%", killer.getName());
        if (victim != null) rawMessage = rawMessage.replace("%victim%", victim.getName());
        return ChatColor.translateAlternateColorCodes('&', rawMessage);
    }

    // --- Death Cries ---
    public String getPlayerDeathCry(Player player) {
        return playerDeathCries.getOrDefault(player.getUniqueId(), "none");
    }

    public void setPlayerDeathCry(Player player, String cryId) {
        UUID uuid = player.getUniqueId();
        
        // Check cooldown
        Long lastChange = cosmeticCooldowns.get(uuid);
        long now = System.currentTimeMillis();
        if (lastChange != null && (now - lastChange) < COSMETIC_COOLDOWN_MS) {
            long remaining = (COSMETIC_COOLDOWN_MS - (now - lastChange)) / 1000;
            player.sendMessage("§cPlease wait " + remaining + " seconds before changing cosmetics again.");
            return;
        }
        
        cosmeticCooldowns.put(uuid, now);
        playerDeathCries.put(uuid, cryId);
        dirtyPlayers.add(uuid);
    }

    public void playDeathCry(Player victim) {
        String cryId = getPlayerDeathCry(victim);
        if (cryId.equalsIgnoreCase("none")) return;

        ConfigurationSection section = plugin.getCosmeticsConfig().getConfigurationSection("death-cries." + cryId);
        if (section == null) return;

        String type = section.getString("type", "none").toLowerCase();
        Location loc = victim.getLocation();

        switch (type) {
            case "anvil": victim.playSound(loc, Sound.ANVIL_LAND, 1f, 1f); break;
            case "ghast": victim.playSound(loc, Sound.GHAST_SCREAM, 1f, 1f); break;
            case "levelup": victim.playSound(loc, Sound.LEVEL_UP, 1f, 1f); break;
            case "dragon": victim.playSound(loc, Sound.ENDERDRAGON_GROWL, 1f, 1f); break;
            case "bat": victim.playSound(loc, Sound.BAT_DEATH, 1f, 1f); break;
            case "blaze": victim.playSound(loc, Sound.BLAZE_DEATH, 1f, 1f); break;
            case "cat": victim.playSound(loc, Sound.CAT_MEOW, 1f, 1f); break;
            case "chicken": victim.playSound(loc, Sound.CHICKEN_HURT, 1f, 1f); break;
            case "cow": victim.playSound(loc, Sound.COW_HURT, 1f, 1f); break;
            case "creeper": victim.playSound(loc, Sound.CREEPER_DEATH, 1f, 1f); break;
            case "enderman": victim.playSound(loc, Sound.ENDERMAN_DEATH, 1f, 1f); break;
            case "pig": victim.playSound(loc, Sound.PIG_DEATH, 1f, 1f); break;
            case "sheep": victim.playSound(loc, Sound.SHEEP_IDLE, 1f, 1f); break;
            case "skeleton": victim.playSound(loc, Sound.SKELETON_DEATH, 1f, 1f); break;
            case "spider": victim.playSound(loc, Sound.SPIDER_DEATH, 1f, 1f); break;
            case "villager": victim.playSound(loc, Sound.VILLAGER_DEATH, 1f, 1f); break;
            case "wolf": victim.playSound(loc, Sound.WOLF_DEATH, 1f, 1f); break;
            case "zombie": victim.playSound(loc, Sound.ZOMBIE_DEATH, 1f, 1f); break;
            case "golem": victim.playSound(loc, Sound.IRONGOLEM_DEATH, 1f, 1f); break;
            case "horse": victim.playSound(loc, Sound.HORSE_DEATH, 1f, 1f); break;
            case "slime": victim.playSound(loc, Sound.SLIME_ATTACK, 1f, 1f); break;
            case "wither": victim.playSound(loc, Sound.WITHER_DEATH, 1f, 1f); break;
            case "burp": victim.playSound(loc, Sound.BURP, 1f, 1f); break;
            case "drink": victim.playSound(loc, Sound.DRINK, 1f, 1f); break;
            case "explode": victim.playSound(loc, Sound.EXPLODE, 1f, 1f); break;
            case "glass": victim.playSound(loc, Sound.GLASS, 1f, 1f); break;
            case "fizz": victim.playSound(loc, Sound.FIZZ, 1f, 1f); break;
        }
    }

    // --- Kill Effects ---
    public String getPlayerKillEffect(Player player) {
        return playerKillEffects.getOrDefault(player.getUniqueId(), "none");
    }

    public void setPlayerKillEffect(Player player, String effectId) {
        UUID uuid = player.getUniqueId();
        
        // Check cooldown
        Long lastChange = cosmeticCooldowns.get(uuid);
        long now = System.currentTimeMillis();
        if (lastChange != null && (now - lastChange) < COSMETIC_COOLDOWN_MS) {
            long remaining = (COSMETIC_COOLDOWN_MS - (now - lastChange)) / 1000;
            player.sendMessage("§cPlease wait " + remaining + " seconds before changing cosmetics again.");
            return;
        }
        
        cosmeticCooldowns.put(uuid, now);
        playerKillEffects.put(uuid, effectId);
        dirtyPlayers.add(uuid);
    }

    @SuppressWarnings("deprecation")
    public void playKillEffect(Player killer, Player victim) {
        String effectId = getPlayerKillEffect(killer);
        if (effectId.equalsIgnoreCase("none")) return;

        ConfigurationSection effectSection = plugin.getCosmeticsConfig().getConfigurationSection("kill-effects." + effectId);
        if (effectSection == null) return;

        String type = effectSection.getString("type", "none").toLowerCase();
        Location loc = victim.getLocation();
        World world = victim.getWorld();

        switch (type) {
            // --- 20 NEW UNIQUE EFFECTS ---
            case "nuke":
                world.playSound(loc, Sound.EXPLODE, 1f, 0.5f);
                for (int i = 0; i < 3; i++) world.createExplosion(loc.getX() + (Math.random()*4-2), loc.getY(), loc.getZ() + (Math.random()*4-2), 0f, false, false);
                for (int i = 0; i < 15; i++) world.playEffect(loc.clone().add(Math.random()*3-1.5, Math.random()*3, Math.random()*3-1.5), Effect.MOBSPAWNER_FLAMES, 0);
                break;
            case "blackhole":
                world.playSound(loc, Sound.ENDERMAN_TELEPORT, 1f, 0.5f);
                for (int i = 0; i < 8; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 49); // Obsidian
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.POTION_BREAK, 8); // Weakness (gray)
                }
                break;
            case "volcano":
                world.playSound(loc, Sound.GHAST_FIREBALL, 1f, 1f);
                for (int i = 0; i < 10; i++) {
                    world.playEffect(loc.clone().add(0, Math.random()*3, 0), Effect.STEP_SOUND, 87); // Netherrack
                    world.playEffect(loc.clone().add(Math.random(), Math.random()*2, Math.random()), Effect.MOBSPAWNER_FLAMES, 0);
                }
                break;
            case "blizzard":
                world.playSound(loc, Sound.MINECART_BASE, 1f, 1.5f);
                for (int i = 0; i < 12; i++) {
                    world.playEffect(loc.clone().add(Math.random()*4-2, Math.random()*3, Math.random()*4-2), Effect.STEP_SOUND, 80); // Snow
                    world.playEffect(loc.clone().add(Math.random()*4-2, Math.random()*3, Math.random()*4-2), Effect.STEP_SOUND, 174); // Packed Ice
                }
                break;
            case "holy":
                world.playSound(loc, Sound.LEVEL_UP, 1f, 1.5f);
                for (int i = 0; i < 6; i++) {
                    world.playEffect(loc.clone().add(0, i * 0.5, 0), Effect.STEP_SOUND, 41); // Gold
                    world.playEffect(loc.clone().add(0, i * 0.5, 0), Effect.POTION_BREAK, 1); // Regen (Pinkish)
                }
                break;
            case "shadow":
                world.playSound(loc, Sound.WITHER_SHOOT, 1f, 0.5f);
                for (int i = 0; i < 10; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 173); // Coal
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.ENDER_SIGNAL, 0);
                }
                break;
            case "nature":
                world.playSound(loc, Sound.WOLF_BARK, 1f, 1f);
                for (int i = 0; i < 8; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 18); // Leaves
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 103); // Melon
                }
                break;
            case "ocean":
                world.playSound(loc, Sound.SPLASH, 1f, 1f);
                for (int i = 0; i < 8; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.POTION_BREAK, 0); // Water
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 168); // Prismarine
                }
                break;
            case "ender_rift":
                world.playSound(loc, Sound.ENDERDRAGON_WINGS, 1f, 1f);
                for (int i = 0; i < 6; i++) {
                    world.playEffect(loc.clone().add(Math.random()*3-1.5, Math.random()*2, Math.random()*3-1.5), Effect.ENDER_SIGNAL, 0);
                    world.playEffect(loc.clone().add(Math.random()*3-1.5, Math.random()*2, Math.random()*3-1.5), Effect.POTION_BREAK, 6); // Night Vis (Dark Blue)
                }
                break;
            case "creeper_prank":
                world.playSound(loc, Sound.CREEPER_HISS, 1f, 1f);
                world.createExplosion(loc.getX(), loc.getY(), loc.getZ(), 0f, false, false);
                world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 4); // Poison (Green)
                break;
            case "halloween":
                world.playSound(loc, Sound.BAT_TAKEOFF, 1f, 1f);
                for (int i = 0; i < 6; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 86); // Pumpkin
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 30); // Web
                }
                break;
            case "disco":
                world.playSound(loc, Sound.NOTE_BASS_GUITAR, 1f, 1f);
                world.playEffect(loc.clone().add(1, 1, 0), Effect.POTION_BREAK, 1);
                world.playEffect(loc.clone().add(-1, 1, 0), Effect.POTION_BREAK, 2);
                world.playEffect(loc.clone().add(0, 1, 1), Effect.POTION_BREAK, 3);
                world.playEffect(loc.clone().add(0, 1, -1), Effect.POTION_BREAK, 4);
                break;
            case "vampire":
                world.playSound(loc, Sound.DRINK, 1f, 1f);
                world.playSound(loc, Sound.BAT_DEATH, 1f, 1f);
                for (int i = 0; i < 8; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 152); // Redstone
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.POTION_BREAK, 5); // Magic (Red)
                }
                break;
            case "toxic_cloud":
                world.playSound(loc, Sound.FIZZ, 1f, 0.5f);
                for (int i = 0; i < 10; i++) {
                    world.playEffect(loc.clone().add(Math.random()*3-1.5, Math.random()*2, Math.random()*3-1.5), Effect.STEP_SOUND, 165); // Slime
                    world.playEffect(loc.clone().add(Math.random()*3-1.5, Math.random()*2, Math.random()*3-1.5), Effect.POTION_BREAK, 4); // Poison
                }
                break;
            case "shatter":
                world.playSound(loc, Sound.ANVIL_BREAK, 1f, 1f);
                world.playSound(loc, Sound.GLASS, 1f, 1f);
                for (int i = 0; i < 8; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 42); // Iron
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 20); // Glass
                }
                break;
            case "thunderstorm":
                world.playSound(loc, Sound.AMBIENCE_THUNDER, 1f, 1f);
                world.strikeLightningEffect(loc);
                for (int i = 0; i < 6; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.POTION_BREAK, 0); // Water
                }
                break;
            case "meteor":
                world.playSound(loc, Sound.FIRE_IGNITE, 1f, 0.5f);
                world.createExplosion(loc.getX(), loc.getY(), loc.getZ(), 0f, false, false);
                for (int i = 0; i < 8; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 87); // Netherrack
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.POTION_BREAK, 3); // Fire Res (Orange)
                }
                break;
            case "glitch":
                world.playSound(loc, Sound.ENDERMAN_SCREAM, 1f, 2f);
                for (int i = 0; i < 10; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 35); // Wool
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.ENDER_SIGNAL, 0);
                }
                break;
            case "wealth":
                world.playSound(loc, Sound.ORB_PICKUP, 1f, 1f);
                for (int i = 0; i < 6; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 57); // Diamond
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 133); // Emerald
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 41); // Gold
                }
                break;
            case "candy":
                world.playSound(loc, Sound.BURP, 1f, 1f);
                for (int i = 0; i < 8; i++) {
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.STEP_SOUND, 83); // Sugar Cane
                    world.playEffect(loc.clone().add(Math.random()*2-1, Math.random()*2, Math.random()*2-1), Effect.POTION_BREAK, 1); // Regen (Pink)
                }
                break;

            // --- STANDARD EFFECTS ---
            case "lightning": world.strikeLightningEffect(loc); break;
            case "explosion": world.createExplosion(loc.getX(), loc.getY(), loc.getZ(), 0f, false, false); break;
            case "ender": world.playEffect(loc.clone().add(0, 1, 0), Effect.ENDER_SIGNAL, 0); break;
            case "fire": world.playEffect(loc.clone().add(0, 1, 0), Effect.MOBSPAWNER_FLAMES, 0); break;

            // Block Break (Color splashes)
            case "blood": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 152); break; // Redstone
            case "slime": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 165); break; // Slime block
            case "snow": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 80); break; // Snow block
            case "gold": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 41); break; // Gold block
            case "diamond": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 57); break; // Diamond
            case "emerald": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 133); break; // Emerald
            case "obsidian": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 49); break; // Obsidian
            case "glass": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 20); break; // Glass
            case "wood": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 5); break; // Planks
            case "sponge": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 19); break; // Sponge
            case "coal": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 173); break; // Coal
            case "lapis": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 22); break; // Lapis
            case "web": world.playEffect(loc.clone().add(0, 1, 0), Effect.STEP_SOUND, 30); break; // Cobweb

            // Potion Breaks (Magic bursts of different colors)
            case "water": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 0); break; // Blue
            case "regen": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 1); break; // Pink
            case "speed": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 2); break; // Light Blue
            case "fire_res": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 3); break; // Orange
            case "poison": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 4); break; // Green
            case "magic": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 5); break; // Red
            case "night_vis": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 6); break; // Dark Blue
            case "weakness": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 8); break; // Gray
            case "strength": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 9); break; // Dark Red
            case "slowness": world.playEffect(loc.clone().add(0, 1, 0), Effect.POTION_BREAK, 10); break; // Brown
        }
    }
}
