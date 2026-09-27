package me.obvgreen.buildffa.listeners.block;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class BlockBreakListener implements Listener {

    private final BlockListenerContext context;

    public BlockBreakListener(BlockListenerContext context) {
        this.context = context;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        if (!context.isInBuildFFAWorld(player) || context.isInBuildMode(player)) {
            return;
        }

        final Block block = event.getBlock();
        final Location blockLoc = block.getLocation().clone();

        if (context.isInsideSpawnRegion(blockLoc) || context.isInsideSpawnRegion(player.getLocation())) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot break blocks inside the spawn protection region!");
            return;
        }

        if (context.getPlacedBlocks().remove(blockLoc)) {
            event.setCancelled(true);

            context.cancelActiveTask(blockLoc);
            block.setType(Material.AIR);

            BlockState originalState = context.getRestoringBlocks().get(blockLoc);
            if (originalState != null) {
                context.scheduleRestore(blockLoc, originalState, context.getBlockRestoreDelayTicks());
            }
            return;
        }

        int maxBuildHeight = context.getMaxBuildHeight();
        if (blockLoc.getY() > maxBuildHeight) {
            event.setCancelled(true);
            return;
        }

        if (context.getRestoringBlocks().containsKey(blockLoc)) {
            event.setCancelled(true);
            block.setType(Material.AIR);
            return;
        }

        final BlockState originalState = block.getState();

        event.setCancelled(true);
        block.setType(Material.AIR);

        context.getRestoringBlocks().put(blockLoc, originalState);
        context.scheduleRestore(blockLoc, originalState, context.getBlockRestoreDelayTicks());
    }
}
