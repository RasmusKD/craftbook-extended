package com.sk89q.craftbook.mechanics.pipe;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.block.Block;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

public class PipeRequestEvent extends PipeSuckEvent {

    private static final HandlerList handlers = new HandlerList();

    private final boolean fromHop;
    private final int hopDepth;
    private final Set<String> usedTeleportPairs;

    public PipeRequestEvent (Block theBlock, List<ItemStack> items, Block sucked) {
        this(theBlock, items, sucked, false, 0);
    }

    /**
     * A request that arrived through a PipeLink hop. Hop requests may start from any valid
     * pipe block (not just a sticky piston), and leftovers are returned to the sucked block's
     * inventory instead of being dropped.
     */
    public PipeRequestEvent (Block theBlock, List<ItemStack> items, Block sucked, boolean fromHop, int hopDepth) {
        super(theBlock, items, sucked);
        this.fromHop = fromHop;
        this.hopDepth = hopDepth;
        this.usedTeleportPairs = new HashSet<>();
    }

    private boolean extract;

    /**
     * Marks this request as an extraction wish list: the items describe what the requester
     * wants pulled OUT of the pipe's source container, they are not real items. Pipes
     * replaces the list with matching items actually removed from the source; nothing is
     * distributed through the network and the wish itself is discarded. The old flow
     * injected the wish into a normal pull cycle, where the leftover-return deposited it
     * into the source container - fabricating one item per request out of thin air.
     */
    public void setExtract(boolean extract) {
        this.extract = extract;
    }

    public boolean isExtract() {
        return extract;
    }

    public boolean isFromHop() {
        return fromHop;
    }

    public int getHopDepth() {
        return hopDepth;
    }

    public boolean wasTeleportUsed(String key) {
        return usedTeleportPairs.contains(key);
    }

    public void markTeleportUsed(String key) {
        usedTeleportPairs.add(key);
    }

    public Set<String> getUsedTeleportPairs() {
        return new HashSet<>(usedTeleportPairs);
    }

    public static String buildTeleportKey(Block sender, Block receiver) {
        return sender.getWorld().getName() + ":" + sender.getX() + "," + sender.getY() + "," + sender.getZ()
                + "->" + receiver.getWorld().getName() + ":" + receiver.getX() + "," + receiver.getY() + "," + receiver.getZ();
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
