package me.obvgreen.buildffa.listeners;

import me.obvgreen.buildffa.listeners.block.BlockListenerContext;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;

public class ItemDropListener implements Listener {

    private final BlockListenerContext context;

    public ItemDropListener(BlockListenerContext context) {
        this.context = context;
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        if (!context.isInBuildFFAWorld(player) || context.isInBuildMode(player)) {
            return;
        }

        event.setCancelled(true);
    }
}
