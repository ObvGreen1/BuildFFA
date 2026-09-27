package me.obvgreen.buildffa.listeners;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CombatListener implements Listener {

    private final BuildFFAPlugin plugin;
    private final Set<UUID> usedDoubleJump = new HashSet<>();

    public CombatListener(BuildFFAPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPotionDrink(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();

        if (!plugin.isArenaWorld(player.getLocation())) {
            return;
        }

        if (event.getItem().getType() == Material.POTION) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.getInventory().removeItem(new ItemStack(Material.GLASS_BOTTLE, 1));
                player.updateInventory();
            });
        }
    }

    @EventHandler
    public void onArmorClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();

        if (!plugin.isArenaWorld(player.getLocation())) {
            return;
        }

        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDoubleJumpUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!plugin.isArenaWorld(player.getLocation())) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (!plugin.getCombatManager().isDoubleJumpFeather(item)) {
            return;
        }

        if (player.isOnGround()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        if (usedDoubleJump.contains(uuid)) {
            return;
        }

        double horizontal = plugin.getConfig().getDouble("double-jump-feather.velocity.horizontal", 1.0D);
        double vertical = plugin.getConfig().getDouble("double-jump-feather.velocity.vertical", 0.8D);

        Vector direction = player.getLocation().getDirection();
        direction.setY(0);

        if (direction.lengthSquared() > 0) {
            direction.normalize().multiply(horizontal);
        }

        direction.setY(vertical);
        player.setVelocity(direction);

        if (plugin.getConfig().getBoolean("double-jump-feather.consume", true)) {
            int amount = item.getAmount();
            if (amount <= 1) {
                player.setItemInHand(null);
            } else {
                item.setAmount(amount - 1);
                player.setItemInHand(item);
            }
            player.updateInventory();
        }

        usedDoubleJump.add(uuid);
        event.setCancelled(true);
    }

    /*
     * Priority set to HIGHEST so it executes after protection plugins like WorldGuard.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();

        if (!plugin.isArenaWorld(victim.getLocation())) return;

        // Process fall damage cancellation before checking if cancelled
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
            return;
        }

        // FIX: If WorldGuard cancelled this attack (safe-zone), completely ignore it.
        if (event.isCancelled()) {
            return;
        }

        if (event instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent entityEvent = (EntityDamageByEntityEvent) event;
            if (entityEvent.getDamager() instanceof Player) {
                Player attacker = (Player) entityEvent.getDamager();
                plugin.getCombatManager().tagPlayer(victim, attacker);
            }
        }

        if (victim.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            Player attacker = plugin.getCombatManager().getAttacker(victim);
            plugin.getCombatManager().handleDeath(victim, attacker, "normal");
        }
    }

    @EventHandler
    public void onPearlThrow(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl) {
            EnderPearl pearl = (EnderPearl) event.getEntity();
            if (pearl.getShooter() instanceof Player) {
                Player player = (Player) pearl.getShooter();
                if (plugin.isArenaWorld(player.getLocation())) {
                    plugin.getCombatManager().addPearl(player, pearl);
                }
            }
        }
    }

    @EventHandler
    public void onPearlHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof EnderPearl) {
            EnderPearl pearl = (EnderPearl) event.getEntity();
            if (pearl.getShooter() instanceof Player) {
                Player player = (Player) pearl.getShooter();
                if (plugin.getCombatManager().getPearl(player) != null) {
                    plugin.getCombatManager().removeActivePearl(player);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isArenaWorld(player.getLocation())) return;

        if (player.isOnGround()) {
            usedDoubleJump.remove(player.getUniqueId());
        }

        int voidY = plugin.getConfig().getInt("death-y-level", 50);

        EnderPearl activePearl = plugin.getCombatManager().getPearl(player);
        if (activePearl != null && activePearl.isValid()) {
            voidY = -55;
        } else if (activePearl != null) {
            plugin.getCombatManager().removeActivePearl(player);
        }

        if (player.getLocation().getY() <= voidY) {
            Player attacker = plugin.getCombatManager().getAttacker(player);
            plugin.getCombatManager().handleDeath(player, attacker, "void");
        }
    }
}