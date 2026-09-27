package me.obvgreen.buildffa.listeners.block;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

public class BlockPlaceListener implements Listener {

    private final BlockListenerContext context;

    public BlockPlaceListener(BlockListenerContext context) {
        this.context = context;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        final Player player = event.getPlayer();

        if (!context.isInBuildFFAWorld(player) || context.isInBuildMode(player)) {
            return;
        }

        final Block block = event.getBlock();
        final Location blockLoc = block.getLocation().clone();

        if (context.isInsideSpawnRegion(blockLoc) || context.isInsideSpawnRegion(player.getLocation())) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot place blocks inside the spawn protection region!");
            return;
        }

        final Material blockType = block.getType();

        @SuppressWarnings("deprecation")
        final byte blockData = block.getData();

        if (blockLoc.getY() > context.getMaxBuildHeight()) {
            event.setCancelled(true);
            return;
        }

        context.getPlacedBlocks().add(blockLoc);

        if (blockType == Material.WOOL) {
            Bukkit.getScheduler().runTaskLater(context.getPlugin(), new Runnable() {
                @SuppressWarnings("deprecation")
                @Override
                public void run() {
                    if (player.isOnline() && context.isInBuildFFAWorld(player)) {
                        player.getInventory().addItem(new ItemStack(Material.WOOL, 1, blockData));
                    }
                }
            }, 1L);
        }

        context.scheduleBlockRemoval(blockLoc, context.getBlockRemoveDelayTicks());
    }
}
