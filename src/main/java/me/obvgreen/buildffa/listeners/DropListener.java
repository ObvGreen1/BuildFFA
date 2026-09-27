package me.obvgreen.buildffa.listeners;

import me.obvgreen.buildffa.BuildFFAPlugin;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;

/**
 * Stops item entities from spawning inside the arena so the map stays clean.
 */
public class DropListener implements Listener {

    private final BuildFFAPlugin plugin;

    public DropListener(BuildFFAPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Location location = event.getEntity().getLocation();
        if (plugin.isArenaWorld(location)) {
            event.setCancelled(true);
        }
    }
}
