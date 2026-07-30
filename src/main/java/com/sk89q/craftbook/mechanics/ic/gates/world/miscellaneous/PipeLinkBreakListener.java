package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.UUID;

/**
 * Cleans up PipeLink bindings when a sender or receiver sign is broken. The sign's
 * persistent data decides what it was; no text matching involved.
 */
public class PipeLinkBreakListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!SignUtil.isSign(e.getBlock()))
            return;
        if (!(e.getBlock().getState() instanceof Sign s))
            return;

        UUID bound = PipeLink.readBoundReceiverUUID(s);
        if (bound != null) {
            PipeLinkIndex.get().unbindSenderAt(s.getWorld().getUID(), s.getX(), s.getY(), s.getZ());
        }

        UUID rid = PipeLink.readReceiverUUID(s);
        if (rid != null) {
            PipeLinkIndex.get().unregisterReceiver(rid);
        }
    }
}
