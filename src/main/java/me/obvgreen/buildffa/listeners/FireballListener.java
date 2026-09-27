package me.obvgreen.buildffa.listeners;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FireballListener implements Listener {

    private final BuildFFAPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final Vector PLAYER_ELEVATOR = new Vector(0.0, 0.9, 0.0);

    public FireballListener(BuildFFAPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFireballUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (player.getItemInHand() == null) return;

            String materialName = plugin.getConfig().getString("fireball.item-material", "FIREBALL");
            if (player.getItemInHand().getType().name().equals(materialName)) {

                event.setCancelled(true);

                // World check
                String targetWorld = plugin.getConfig().getString("fireball.world", plugin.getArenaWorldName());
                if (!player.getWorld().getName().equalsIgnoreCase(targetWorld)) return;

                // Cooldown Check
                int cooldownSeconds = plugin.getConfig().getInt("fireball.cooldown", 2);
                if (cooldownSeconds > 0) {
                    long timeLeft = cooldowns.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
                    if (timeLeft > 0) {
                        String msg = plugin.getConfig().getString("fireball.messages.on-cooldown", "&cFireball is on cooldown for %time%s!");
                        msg = msg.replace("%time%", String.valueOf((timeLeft / 1000) + 1));
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                        return;
                    }
                }

                // Remove 1 item
                ItemStack item = player.getItemInHand();
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    player.setItemInHand(null);
                }

                // Apply Cooldown
                if (cooldownSeconds > 0) {
                    cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + (cooldownSeconds * 1000L));
                }

                // Launch the Fireball entity
                Fireball fireball = player.launchProjectile(Fireball.class);
                fireball.setYield(0); // Prevents vanilla block damage

                // Set Speed (FIXED: Uses player's direction instead of fireball's initial 0 velocity)
                double speed = plugin.getConfig().getDouble("fireball.speed", 1.5);
                fireball.setVelocity(player.getLocation().getDirection().multiply(speed));

                // --- NEW: Disappear after 5 seconds if still in the air ---
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (fireball.isValid()) {
                        fireball.remove();
                    }
                }, 5 * 20L); // 5 seconds * 20 ticks/second
            }
        }
    }

    @EventHandler
    public void onFireballHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Fireball)) return;

        Fireball fireball = (Fireball) event.getEntity();
        if (!(fireball.getShooter() instanceof Player)) return;

        Player shooter = (Player) fireball.getShooter();
        Location hitLocation = fireball.getLocation();
        Vector fireballVector = hitLocation.toVector();

        // Config values
        String targetWorld = plugin.getConfig().getString("fireball.world", plugin.getArenaWorldName());
        double explosionSize = plugin.getConfig().getDouble("fireball.explosion-size", 4.0);
        double horizontalMultiplier = plugin.getConfig().getDouble("fireball.knockback.horizontal", 1.2);
        double verticalMultiplier = plugin.getConfig().getDouble("fireball.knockback.vertical", 1.0);
        double baseVertical = plugin.getConfig().getDouble("fireball.knockback.base-vertical", 0.5);
        double selfDamage = plugin.getConfig().getDouble("fireball.damage.self", 2.0);
        double enemyDamage = plugin.getConfig().getDouble("fireball.damage.enemy", 4.0);

        // Loop through nearby entities
        for (Entity entity : hitLocation.getWorld().getNearbyEntities(hitLocation, explosionSize, explosionSize, explosionSize)) {
            if (!(entity instanceof Player)) continue;

            Player player = (Player) entity;

            if (!player.getWorld().getName().equalsIgnoreCase(targetWorld)) continue;

            Vector playerVector = player.getLocation().toVector().add(PLAYER_ELEVATOR);
            double distance = playerVector.distance(fireballVector);

            if (distance <= explosionSize) {
                // 1. Calculate Knockback
                Vector knockback = playerVector.clone().subtract(fireballVector).normalize();
                knockback.setY(knockback.getY() + baseVertical);

                double distanceMultiplier;
                if (distance < explosionSize / 2.0) {
                    distanceMultiplier = -0.2 * (1.0 / explosionSize) * distance + 1.0;
                } else {
                    distanceMultiplier = -(1.0 / explosionSize) * distance + 1.25;
                }

                knockback.setY(distanceMultiplier * knockback.getY() * verticalMultiplier);

                double horizontalNormalized = Math.hypot(knockback.getX(), knockback.getZ());
                if (horizontalNormalized > 0.7) {
                    double k = 0.7 / horizontalNormalized;
                    knockback.setX(knockback.getX() * k);
                    knockback.setZ(knockback.getZ() * k);
                }

                knockback.setX(knockback.getX() * horizontalMultiplier);
                knockback.setZ(knockback.getZ() * horizontalMultiplier);

                Vector finalVelocity = player.getVelocity().multiply(0.85).add(knockback);
                player.setVelocity(finalVelocity);

                // 2. Combat Tagging & Damage
                if (!player.equals(shooter)) {
                    plugin.getCombatManager().tagPlayer(player, shooter);
                    player.damage(enemyDamage);
                } else {
                    EntityDamageEvent selfDamageEvent = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, selfDamage);
                    Bukkit.getPluginManager().callEvent(selfDamageEvent);
                    if (!selfDamageEvent.isCancelled()) {
                        player.setHealth(Math.max(0, player.getHealth() - selfDamage));
                    }
                }
            }
        }
    }

    @EventHandler
    public void onFireballDirectHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Fireball && event.getEntity() instanceof Player) {
            String targetWorld = plugin.getConfig().getString("fireball.world", plugin.getArenaWorldName());
            if (event.getEntity().getWorld().getName().equalsIgnoreCase(targetWorld)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onFireballExplodeBlocks(EntityExplodeEvent event) {
        if (event.getEntity() instanceof Fireball) {
            String targetWorld = plugin.getConfig().getString("fireball.world", plugin.getArenaWorldName());
            if (event.getEntity().getWorld().getName().equalsIgnoreCase(targetWorld)) {
                event.blockList().clear();
            }
        }
    }
}