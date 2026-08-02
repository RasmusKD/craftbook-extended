package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.UUID;

/**
 * Cleans up PipeLink bindings when a sender or receiver sign is broken. The cleanup in
 * onBreak is PDC-driven; the break GUARD below deliberately matches sign text instead,
 * so an unbound-but-labelled sign is also protected while its owner is mid-binding.
 */
public class PipeLinkBreakListener implements Listener {

    /**
     * PipeLink signs cannot be broken while holding the bind/inspect tools, so
     * left-clicking with them (instant break in creative!) is always safe. Switch to any
     * other item to actually remove the sign - which also removes its link.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreakGuard(BlockBreakEvent e) {
        if (!SignUtil.isSign(e.getBlock()))
            return;
        org.bukkit.Material inHand = e.getPlayer().getInventory().getItemInMainHand().getType();
        if (inHand != org.bukkit.Material.BLAZE_ROD && inHand != org.bukkit.Material.BREEZE_ROD)
            return;
        if (!(e.getBlock().getState() instanceof Sign s))
            return;
        if (PipeLinkBindListener.isSenderIC(s) || PipeLinkBindListener.isReceiverIC(s)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(org.bukkit.ChatColor.GRAY + "[Pipes] Skiltet kan ikke brydes med bind-værktøjet i hånden — skift item for at fjerne det (og dermed linket).");
        }
    }

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
