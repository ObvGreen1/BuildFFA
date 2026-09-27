package me.obvgreen.buildffa.listeners;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public class SpawnProtectionListener implements Listener {

    private final BuildFFAPlugin plugin;

    public SpawnProtectionListener(BuildFFAPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();
        if (plugin.getRegionManager().isInside(victim.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();
        Player attacker = getAttacker(event.getDamager());

        boolean victimInside = plugin.getRegionManager().isInside(victim.getLocation());
        boolean attackerInside = attacker != null && plugin.getRegionManager().isInside(attacker.getLocation());

        if (victimInside || attackerInside) {
            event.setCancelled(true);
            if (attacker != null) {
                attacker.sendMessage("§cYou cannot attack inside the spawn protection region!");
            }
        }
    }

    private Player getAttacker(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            if (((Projectile) damager).getShooter() instanceof Player) {
                return (Player) ((Projectile) damager).getShooter();
            }
        }
        return null;
    }
}