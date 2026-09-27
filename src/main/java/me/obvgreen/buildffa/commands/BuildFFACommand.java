package me.obvgreen.buildffa.commands;

import me.obvgreen.buildffa.BuildFFAPlugin;
import me.obvgreen.buildffa.cosmetics.CosmeticsGUI;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;
import java.util.stream.Collectors;

public class BuildFFACommand implements CommandExecutor, TabCompleter, Listener {

    private final BuildFFAPlugin plugin;
    private final Random random = new Random();

    public static final Set<UUID> buildMode = new HashSet<>();
    public static final Set<UUID> playersInFFA = new HashSet<>();
    private static final List<String> SUBCOMMANDS = Arrays.asList("join", "leave", "reload", "build", "stats", "hotbarmanager", "hotbarorder", "cosmetics", "wipe", "modify", "pos1", "pos2");
    private static final List<String> STAT_TYPES = Arrays.asList("kills", "deaths", "killstreak", "max_streak");

    private final Map<String, ItemStack> itemTemplates = new HashMap<>();

    public BuildFFACommand(BuildFFAPlugin plugin) {
        this.plugin = plugin;
        setupItemTemplates();
    }

    private void setupItemTemplates() {
        itemTemplates.put("SWORD", createItem(Material.STONE_SWORD, 1, Enchantment.DAMAGE_ALL, 2));
        itemTemplates.put("WOOL", new ItemStack(Material.WOOL, 64, (short) 3));
        itemTemplates.put("AXE", createItem(Material.WOOD_AXE, 1, Enchantment.DIG_SPEED, 1));
        itemTemplates.put("PICKAXE", createItem(Material.IRON_PICKAXE, 1, Enchantment.DIG_SPEED, 1));
        itemTemplates.put("SHEARS", createItem(Material.SHEARS, 1, null, 0));
        itemTemplates.put("LEGGINGS", createItem(Material.DIAMOND_LEGGINGS, 1, Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        itemTemplates.put("BOOTS", createItem(Material.DIAMOND_BOOTS, 1, Enchantment.PROTECTION_ENVIRONMENTAL, 2));

        // Pre-bake both color variants once at startup instead of doing
        // getItemMeta() -> setColor() -> setItemMeta() (2 extra CraftMetaItem
        // clones) on every single FFAJoin() call.
        itemTemplates.put("HELMET_GREEN", createColoredLeatherItem(Material.LEATHER_HELMET, Color.GREEN));
        itemTemplates.put("HELMET_RED", createColoredLeatherItem(Material.LEATHER_HELMET, Color.RED));
        itemTemplates.put("CHESTPLATE_GREEN", createColoredLeatherItem(Material.LEATHER_CHESTPLATE, Color.GREEN));
        itemTemplates.put("CHESTPLATE_RED", createColoredLeatherItem(Material.LEATHER_CHESTPLATE, Color.RED));
    }

    private ItemStack createColoredLeatherItem(Material material, Color color) {
        ItemStack item = createItem(material, 1, Enchantment.PROTECTION_ENVIRONMENTAL, 2);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /buildffa <subcommand>");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (sender instanceof Player) {
            Player player = (Player) sender;
            switch (subCommand) {
                case "join":
                    // Check if player is already in the buildffa world
                    if (plugin.isArenaWorld(player.getLocation())) {
                        player.sendMessage("§cYou are already in the BuildFFA world!");
                        return true;
                    }

                    World buildffaWorld = Bukkit.getWorld(plugin.getArenaWorldName());
                    if (buildffaWorld != null) {
                        player.teleport(plugin.getArenaSpawn());
                        FFAJoin(player);
                    } else {
                        player.sendMessage("§cError: The '" + plugin.getArenaWorldName() + "' world does not exist or is not loaded.");
                    }
                    return true;
                case "leave":
                    FFALeave(player);
                    return true;
                case "hotbarmanager":
                    plugin.getHotbarManager().openGUI(player);
                    return true;
                case "hotbarorder":
                    if (args.length >= 2) {
                        if (!player.hasPermission("buildffa.admin")) {
                            player.sendMessage("§cYou do not have permission to view other players' hotbar orders.");
                            return true;
                        }
                        OfflinePlayer targetOrder = Bukkit.getOfflinePlayer(args[1]);
                        if (!targetOrder.hasPlayedBefore() && !targetOrder.isOnline()) {
                            player.sendMessage("§cPlayer not found: " + args[1]);
                            return true;
                        }
                        sendHotbarOrder(player, targetOrder.getUniqueId(), targetOrder.getName());
                    } else {
                        sendHotbarOrder(player, player.getUniqueId(), player.getName());
                    }
                    return true;
                case "cosmetics":
                    CosmeticsGUI.openMainMenu(player);
                    return true;
            }
        } else if (subCommand.equals("join") || subCommand.equals("leave") || subCommand.equals("hotbarmanager") || subCommand.equals("cosmetics") || subCommand.equals("hotbarorder")) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return true;
        }

        if (subCommand.equals("stats")) {
            if (args.length < 2) {
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    UUID uuid = player.getUniqueId();

                    sender.sendMessage("§3§lYour BuildFFA Stats:");
                    sender.sendMessage("§b◆ Kills: §f" + plugin.getStatsManager().getKills(uuid));
                    sender.sendMessage("§b◆ Deaths: §f" + plugin.getStatsManager().getDeaths(uuid));
                    sender.sendMessage("§b◆ Current KillStreak: §f" + plugin.getStatsManager().getKillstreak(uuid));
                    sender.sendMessage("§b◆ Max KillStreak: §f" + plugin.getStatsManager().getMaxKillstreak(uuid));
                } else {
                    sender.sendMessage("§cPlease specify a player to view stats.");
                }
            } else {
                OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(args[1]);
                if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
                    sender.sendMessage("§cPlayer not found: " + args[1]);
                    return true;
                }

                UUID targetUUID = targetPlayer.getUniqueId();
                sender.sendMessage("§3§lBuildFFA Stats for " + targetPlayer.getName() + ":");
                sender.sendMessage("§b◆ Kills: §f" + plugin.getStatsManager().getKills(targetUUID));
                sender.sendMessage("§b◆ Deaths: §f" + plugin.getStatsManager().getDeaths(targetUUID));
                sender.sendMessage("§b◆ Current KillStreak: §f" + plugin.getStatsManager().getKillstreak(targetUUID));
                sender.sendMessage("§b◆ Max KillStreak: §f" + plugin.getStatsManager().getMaxKillstreak(targetUUID));
            }
            return true;
        }

        if (!sender.hasPermission("buildffa.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        switch (subCommand) {
            case "build":
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cOnly players can toggle build mode.");
                    return true;
                }
                Player builder = (Player) sender;
                if (buildMode.contains(builder.getUniqueId())) {
                    buildMode.remove(builder.getUniqueId());
                    builder.sendMessage("§cBuild mode disabled.");
                } else {
                    buildMode.add(builder.getUniqueId());
                    builder.sendMessage("§aBuild mode enabled. You can now freely edit the map.");
                }
                break;

            case "reload":
                plugin.reloadCustomConfigs();
                sender.sendMessage("§aBuildFFA configuration and files reloaded!");
                break;

            case "wipe":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /buildffa wipe <player>");
                    return true;
                }
                OfflinePlayer playerToWipe = Bukkit.getOfflinePlayer(args[1]);
                if (!playerToWipe.hasPlayedBefore() && !playerToWipe.isOnline()) {
                    sender.sendMessage("§cPlayer not found: " + args[1]);
                    return true;
                }
                plugin.getStatsManager().wipeStats(playerToWipe.getUniqueId());
                sender.sendMessage("§aStats for " + playerToWipe.getName() + " have been wiped.");
                break;

            case "modify":
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /buildffa modify <stat> <amount> <player>");
                    sender.sendMessage("§eAvailable stats: kills, deaths, killstreak, max_streak");
                    return true;
                }

                String statToModify = args[1].toLowerCase();
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid amount. Please enter a whole number.");
                    return true;
                }

                OfflinePlayer targetPlayerModify = Bukkit.getOfflinePlayer(args[3]);
                if (!targetPlayerModify.hasPlayedBefore() && !targetPlayerModify.isOnline()) {
                    sender.sendMessage("§cPlayer not found: " + args[3]);
                    return true;
                }

                UUID targetUUID = targetPlayerModify.getUniqueId();
                switch (statToModify) {
                    case "kills":
                        plugin.getStatsManager().addKills(targetUUID, amount);
                        break;
                    case "deaths":
                        plugin.getStatsManager().addDeaths(targetUUID, amount);
                        break;
                    case "winstreak":
                    case "killstreak":
                        plugin.getStatsManager().addCurrentKillstreak(targetUUID, amount);
                        break;
                    case "max_streak":
                    case "maxstreak":
                        plugin.getStatsManager().addMaxKillstreak(targetUUID, amount);
                        break;
                    default:
                        sender.sendMessage("§cUnknown stat: " + statToModify + ". Available: kills, deaths, killstreak, max_streak.");
                        return true;
                }

                sender.sendMessage("§aModified " + statToModify + " by " + amount + " for " + targetPlayerModify.getName() + ".");
                break;

            case "pos1":
                setSpawnPosition(sender, args, 1);
                break;

            case "pos2":
                setSpawnPosition(sender, args, 2);
                break;

            default:
                sender.sendMessage("§cUnknown subcommand. Usage: /buildffa <subcommand>");
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(sub -> sub.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("modify")) {
            return STAT_TYPES.stream()
                    .filter(stat -> stat.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("stats") || args[0].equalsIgnoreCase("wipe") || args[0].equalsIgnoreCase("hotbarorder"))) {
            return null;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("modify")) {
            return null;
        }
        if ((args[0].equalsIgnoreCase("pos1") || args[0].equalsIgnoreCase("pos2")) && args.length >= 2) {
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private void setSpawnPosition(CommandSender sender, String[] args, int pos) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can set spawn protection positions.");
            return;
        }
        Player player = (Player) sender;
        String posName = "pos" + pos;

        Location loc;
        if (args.length == 1) {
            loc = player.getLocation();
        } else if (args.length == 4) {
            try {
                double x = Double.parseDouble(args[1]);
                double y = Double.parseDouble(args[2]);
                double z = Double.parseDouble(args[3]);
                loc = new Location(player.getWorld(), x, y, z);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid coordinates. Usage: /buildffa " + posName + " [x y z] or /buildffa " + posName + " [world x y z]");
                return;
            }
        } else if (args.length == 5) {
            World world = Bukkit.getWorld(args[1]);
            if (world == null) {
                sender.sendMessage("§cWorld not found: " + args[1]);
                return;
            }
            try {
                double x = Double.parseDouble(args[2]);
                double y = Double.parseDouble(args[3]);
                double z = Double.parseDouble(args[4]);
                loc = new Location(world, x, y, z);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid coordinates. Usage: /buildffa " + posName + " [x y z] or /buildffa " + posName + " [world x y z]");
                return;
            }
        } else {
            sender.sendMessage("§cUsage: /buildffa " + posName + " [x y z] or /buildffa " + posName + " [world x y z]");
            return;
        }

        plugin.getRegionManager().setPosition(pos, loc);
        sender.sendMessage("§aSpawn protection " + posName + " set to (" + formatCoord(loc.getX()) + ", " + formatCoord(loc.getY()) + ", " + formatCoord(loc.getZ()) + ") in world '" + loc.getWorld().getName() + "'.");

        if (plugin.getRegionManager().isRegionDefined()) {
            Location p1 = plugin.getRegionManager().getPosition(1);
            Location p2 = plugin.getRegionManager().getPosition(2);
            sender.sendMessage("§aSpawn protection region is now active!");
            sender.sendMessage("§eBox from (" + formatCoord(p1.getX()) + ", " + formatCoord(p1.getY()) + ", " + formatCoord(p1.getZ()) + ") to (" + formatCoord(p2.getX()) + ", " + formatCoord(p2.getY()) + ", " + formatCoord(p2.getZ()) + ") in world '" + p1.getWorld().getName() + "'.");
            sender.sendMessage("§ePlayers inside this box cannot place/break blocks or attack others, and cannot be attacked.");
        } else {
            sender.sendMessage("§eSpawn protection needs 2 positions. Set " + (pos == 1 ? "pos2" : "pos1") + " to activate it.");
        }
    }

    private String formatCoord(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    public void FFAJoin(Player player) {
        UUID uuid = player.getUniqueId();
        playersInFFA.add(uuid);

        plugin.getCombatManager().removeActivePearl(player);
        plugin.getStatsManager().resetKillstreak(uuid);

        player.setGameMode(GameMode.SURVIVAL);
        if (player.getAllowFlight()) player.setAllowFlight(false);
        if (player.isFlying()) player.setFlying(false);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Collection<PotionEffect> effects = player.getActivePotionEffects();
            if (!effects.isEmpty()) {
                for (PotionEffect effect : effects) {
                    player.removePotionEffect(effect.getType());
                }
            }
        }, 1L);

        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(null);

        me.obvgreen.buildffa.managers.HotbarManager hm = plugin.getHotbarManager();

        inv.setItem(hm.getSlot(uuid, "SWORD"), itemTemplates.get("SWORD").clone());
        inv.setItem(hm.getSlot(uuid, "WOOL"), itemTemplates.get("WOOL").clone());
        inv.setItem(hm.getSlot(uuid, "AXE"), itemTemplates.get("AXE").clone());
        inv.setItem(hm.getSlot(uuid, "PICKAXE"), itemTemplates.get("PICKAXE").clone());
        inv.setItem(hm.getSlot(uuid, "SHEARS"), itemTemplates.get("SHEARS").clone());

        boolean green = random.nextBoolean();
        inv.setHelmet((green ? itemTemplates.get("HELMET_GREEN") : itemTemplates.get("HELMET_RED")).clone());
        inv.setChestplate((green ? itemTemplates.get("CHESTPLATE_GREEN") : itemTemplates.get("CHESTPLATE_RED")).clone());

        inv.setLeggings(itemTemplates.get("LEGGINGS").clone());
        inv.setBoots(itemTemplates.get("BOOTS").clone());

        setupHealthIndicator(player);
    }

    private void sendHotbarOrder(Player sender, UUID targetUUID, String targetName) {
        me.obvgreen.buildffa.managers.HotbarManager hm = plugin.getHotbarManager();
        java.util.Map<String, Integer> layout = hm.getLayout(targetUUID);

        String[] itemNames = {"Sword", "Blocks", "Axe", "Pickaxe", "Shears", "Golden Apple", "Fireball", "Ender Pearl", "Feather"};
        String[] itemKeys = {"SWORD", "WOOL", "AXE", "PICKAXE", "SHEARS", "GAPPLE", "FIREBALL", "PEARL", "FEATHER"};

        sender.sendMessage("§3§lHotbar Order for " + targetName + ":");

        // Build reverse map: slot -> item name
        Map<Integer, String> slotToItem = new HashMap<>();
        for (int i = 0; i < itemKeys.length; i++) {
            int slot = layout.getOrDefault(itemKeys[i], i);
            slotToItem.put(slot, itemNames[i]);
        }

        for (int slot = 0; slot < 9; slot++) {
            String item = slotToItem.getOrDefault(slot, "Empty");
            sender.sendMessage("§bSlot " + slot + ": §f" + item);
        }
    }

    public void FFALeave(Player player) {
        if (!plugin.isArenaWorld(player.getLocation())) {
            return;
        }
        UUID uuid = player.getUniqueId();

        playersInFFA.remove(player.getUniqueId());

        plugin.getCombatManager().removeActivePearl(player);
        plugin.getStatsManager().resetKillstreak(uuid);

        player.setGameMode(GameMode.SURVIVAL);

        World lobbyWorld = Bukkit.getWorld(plugin.getLobbyWorldName());
        if (lobbyWorld != null) {
            Location lobbySpawn = plugin.getLobbySpawn();
            lobbySpawn.setYaw(180f);
            lobbySpawn.setPitch(0f);
            player.teleport(lobbySpawn);
        } else {
            player.sendMessage("§cError: The '" + plugin.getLobbyWorldName() + "' world does not exist or is not loaded.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        buildMode.remove(player.getUniqueId());
        playersInFFA.remove(player.getUniqueId());

        if (plugin.isArenaWorld(player.getLocation())) {
            plugin.getCombatManager().checkAndApplyCombatLog(player);
            plugin.getCombatManager().removeActivePearl(player);
            removeHealthIndicator(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (event.getTo() == null) return;

        if (plugin.isArenaWorld(event.getFrom()) && !plugin.isArenaWorld(event.getTo())) {
            boolean wasInCombat = plugin.getCombatManager().isInCombat(player);
            plugin.getCombatManager().checkAndApplyCombatLog(player);
            if (wasInCombat) {
                player.sendMessage("§cYou combat logged!");
            }

            plugin.getCombatManager().removeActivePearl(player);
            plugin.getCombatManager().removeTag(player);
            buildMode.remove(player.getUniqueId());
            playersInFFA.remove(player.getUniqueId());
            removeHealthIndicator(player);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!plugin.isArenaWorld(player.getLocation())) {
                    Collection<PotionEffect> effects = player.getActivePotionEffects();
                    if (!effects.isEmpty()) {
                        for (PotionEffect effect : effects) {
                            player.removePotionEffect(effect.getType());
                        }
                    }
                    player.getInventory().clear();
                    player.getInventory().setArmorContents(null);
                }
            }, 2L);
        }
    }

    private void setupHealthIndicator(Player player) {
        Scoreboard board = player.getScoreboard();

        if (board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }

        Objective healthObjective = board.getObjective("ffa_health");
        if (healthObjective == null) {
            healthObjective = board.registerNewObjective("ffa_health", "health");
            healthObjective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            healthObjective.setDisplayName("§c❤");
        }
    }

    private void removeHealthIndicator(Player player) {
        Scoreboard board = player.getScoreboard();
        Objective healthObjective = board.getObjective("ffa_health");
        if (healthObjective != null) {
            healthObjective.unregister();
        }
    }

    private ItemStack createItem(Material material, int amount, Enchantment enchant, int level) {
        ItemStack item = new ItemStack(material, amount);
        if (enchant != null) {
            item.addUnsafeEnchantment(enchant, level);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.spigot().setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }
}