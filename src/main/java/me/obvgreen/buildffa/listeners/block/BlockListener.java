package me.obvgreen.buildffa.listeners.block;

import me.obvgreen.buildffa.listeners.ItemDropListener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class BlockListener {

    private final BlockListenerContext context;

    public BlockListener(Plugin plugin) {
        this.context = new BlockListenerContext(plugin);
    }

    public void registerListeners(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(new BlockPlaceListener(context), plugin);
        plugin.getServer().getPluginManager().registerEvents(new BlockBreakListener(context), plugin);
        plugin.getServer().getPluginManager().registerEvents(new ItemDropListener(context), plugin);
    }

    public BlockListenerContext getContext() {
        return context;
    }
}
