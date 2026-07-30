package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.mechanics.pipe.PipeFilterEvent;
import com.sk89q.craftbook.mechanics.pipe.PipeFinishEvent;
import com.sk89q.craftbook.mechanics.pipe.PipePutEvent;
import com.sk89q.craftbook.mechanics.pipe.PipeRequestEvent;
import com.sk89q.craftbook.mechanics.pipe.PipeSuckEvent;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Logs every pipe event. Only registered when the CraftBook debug flag "pipes" is enabled.
 */
public class PipeAllDebugListener implements Listener {

    private static String at(Block b) {
        return b.getWorld().getName() + " " + b.getX() + " " + b.getY() + " " + b.getZ();
    }

    private static String signL0(Block b) {
        return b.getState() instanceof Sign s ? " l0='" + s.getLine(0) + "'" : "";
    }

    @EventHandler(ignoreCancelled = false)
    public void onRequest(PipeRequestEvent e) {
        Bukkit.getLogger().info("[Pipes/DBG] Request -> " + at(e.getBlock()) + signL0(e.getBlock()) + " items=" + e.getItems().size());
    }

    @EventHandler(ignoreCancelled = false)
    public void onPut(PipePutEvent e) {
        Bukkit.getLogger().info("[Pipes/DBG] Put     -> dest=" + at(e.getPuttingBlock()) + signL0(e.getPuttingBlock()) + " items=" + e.getItems().size());
    }

    @EventHandler(ignoreCancelled = false)
    public void onSuck(PipeSuckEvent e) {
        Bukkit.getLogger().info("[Pipes/DBG] Suck    -> from=" + at(e.getSuckedBlock()) + " into=" + at(e.getBlock()) + " items=" + e.getItems().size());
    }

    @EventHandler(ignoreCancelled = false)
    public void onFilter(PipeFilterEvent e) {
        Bukkit.getLogger().info("[Pipes/DBG] Filter  -> " + at(e.getBlock()) + " items=" + e.getItems().size());
    }

    @EventHandler(ignoreCancelled = false)
    public void onFinish(PipeFinishEvent e) {
        Bukkit.getLogger().info("[Pipes/DBG] Finish  -> " + at(e.getBlock()) + " leftovers=" + e.getItems().size());
    }
}
