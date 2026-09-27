package me.obvgreen.buildffa.listeners.block;

import me.obvgreen.buildffa.BuildFFAPlugin;
import me.obvgreen.buildffa.commands.BuildFFACommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BlockListenerContext {



    private static final String CFG_MAX_BUILD_HEIGHT = "max-build-height";
    private static final String CFG_BLOCK_REMOVE_DELAY = "block-remove-delay";
    private static final String CFG_BLOCK_RESTORE_DELAY = "block-restore-delay";

    private final Plugin plugin;

    private final Set<Location> placedBlocks = Collections.synchronizedSet(new HashSet<Location>());
    private final Map<Location, BlockState> restoringBlocks = Collections.synchronizedMap(new HashMap<Location, BlockState>());
    private final Map<Location, BukkitTask> activeTasks = Collections.synchronizedMap(new HashMap<Location, BukkitTask>());

    public BlockListenerContext(Plugin plugin) {
        this.plugin = plugin;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public Set<Location> getPlacedBlocks() {
        return placedBlocks;
    }

    public Map<Location, BlockState> getRestoringBlocks() {
        return restoringBlocks;
    }

    public Map<Location, BukkitTask> getActiveTasks() {
        return activeTasks;
    }

    public boolean isInBuildFFAWorld(Player player) {
        return plugin instanceof BuildFFAPlugin && ((BuildFFAPlugin) plugin).isArenaWorld(player.getLocation());
    }

    public boolean isInBuildMode(Player player) {
        UUID uuid = player.getUniqueId();
        return BuildFFACommand.buildMode.contains(uuid);
    }

    public boolean isInsideSpawnRegion(Location location) {
        if (plugin instanceof BuildFFAPlugin) {
            return ((BuildFFAPlugin) plugin).getRegionManager().isInside(location);
        }
        return false;
    }

    public int getMaxBuildHeight() {
        return plugin.getConfig().getInt(CFG_MAX_BUILD_HEIGHT, 99);
    }

    public long getBlockRemoveDelayTicks() {
        return plugin.getConfig().getInt(CFG_BLOCK_REMOVE_DELAY, 8) * 20L;
    }

    public long getBlockRestoreDelayTicks() {
        return plugin.getConfig().getInt(CFG_BLOCK_RESTORE_DELAY, 9) * 20L;
    }

    public void cancelActiveTask(Location location) {
        BukkitTask oldTask = activeTasks.remove(location);
        if (oldTask != null) {
            oldTask.cancel();
        }
    }

    public void scheduleBlockRemoval(final Location location, final long delayTicks) {
        cancelActiveTask(location);

        BukkitTask newTask = new BukkitRunnable() {
            long currentTick = 0;
            final long totalTicks = delayTicks;

            @Override
            public void run() {
                currentTick++;

                if (currentTick >= totalTicks) {
                    finishBlockRemoval(location);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        activeTasks.put(location, newTask);
    }

    public void finishBlockRemoval(Location location) {
        placedBlocks.remove(location);
        activeTasks.remove(location);

        BlockState restoreState = restoringBlocks.get(location);
        if (restoreState != null) {
            restoringBlocks.remove(location);
            restoreState.update(true, false);
        } else {
            location.getBlock().setType(Material.AIR);
        }
    }

    public void scheduleRestore(final Location location, final BlockState originalState, final long delayTicks) {
        cancelActiveTask(location);

        BukkitTask newTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                placedBlocks.remove(location);
                restoringBlocks.remove(location);
                activeTasks.remove(location);

                Block currentBlock = location.getBlock();

                if (currentBlock.getType() != Material.AIR) {
                    currentBlock.setType(Material.AIR);
                }

                originalState.update(true, false);
            }
        }, delayTicks);

        activeTasks.put(location, newTask);
    }
}
