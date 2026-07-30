package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.mechanics.pipe.PipePutEvent;
import com.sk89q.craftbook.mechanics.pipe.PipeRequestEvent;
import com.sk89q.craftbook.mechanics.pipe.PipeSuckEvent;
import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes pipe traffic through PipeLinks. All lookups go through {@link PipeLinkIndex}, so
 * a pipe event on a server without any links costs a single empty-map check and no block
 * state reads.
 */
public class PipeLinkRouter implements Listener {

    private static final PipeLinkRouter INSTANCE = new PipeLinkRouter();

    public static PipeLinkRouter get() {
        return INSTANCE;
    }

    private static final BlockFace[] NEIGHBOUR_FACES = {
        BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH, BlockFace.UP, BlockFace.DOWN
    };

    private static final int MAX_CHAIN_DEPTH = 16;
    private static int chainDepth = 0;

    /** Last candidate index that accepted items, per receiver, so steady-state transfers probe once. */
    private final Map<UUID, Integer> lastGoodCandidate = new ConcurrentHashMap<>();

    /**
     * Receivers whose network refused everything (typically a full container) are put on a
     * short cooldown, so a clocked sender does not re-traverse the receiver's whole pipe
     * network several times per pulse while it is blocked.
     */
    private static final long RETRY_COOLDOWN_MILLIS = 1000L;
    private final Map<UUID, Long> deliveryBackoff = new ConcurrentHashMap<>();

    /* Event routing -------------------------------------------------------------------- */

    @EventHandler(ignoreCancelled = true)
    public void onPut(PipePutEvent e) {
        SenderHit hit = findSender(e.getPuttingBlock(), false);
        if (hit == null)
            return;
        List<ItemStack> remaining = sendThroughLink(hit.signBlock, hit.receiverId, e.getItems(), null);
        if (remaining != null) {
            e.setItems(remaining);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRequest(PipeRequestEvent e) {
        SenderHit hit = findSender(e.getBlock(), false);
        if (hit == null)
            return;
        List<ItemStack> remaining = sendThroughLink(hit.signBlock, hit.receiverId, e.getItems(), e.getUsedTeleportPairs());
        if (remaining != null) {
            e.setItems(remaining);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSuck(PipeSuckEvent e) {
        if (e instanceof PipeRequestEvent)
            return; // handled by onRequest
        SenderHit hit = findSender(e.getBlock(), true);
        if (hit == null)
            return;
        List<ItemStack> remaining = sendThroughLink(hit.signBlock, hit.receiverId, e.getItems(), null);
        if (remaining != null) {
            e.setItems(remaining);
            e.setCancelled(true);
        }
    }

    /**
     * Finds a bound sender sign at the target block or adjacent to it (facing the target;
     * with includeBack also mounted on it), using only index lookups until a hit is found.
     */
    private SenderHit findSender(Block target, boolean includeBack) {
        PipeLinkIndex index = PipeLinkIndex.get();
        UUID world = target.getWorld().getUID();
        if (index.hasNoSenders(world))
            return null;

        UUID rid = index.getBoundReceiverAt(world, PipeLinkIndex.posKey(target));
        if (rid != null && SignUtil.isSign(target))
            return new SenderHit(target, rid);

        for (BlockFace face : NEIGHBOUR_FACES) {
            Block nb = target.getRelative(face);
            UUID nbRid = index.getBoundReceiverAt(world, PipeLinkIndex.posKey(nb));
            if (nbRid == null || !SignUtil.isSign(nb))
                continue;
            Block front = SignUtil.getFrontBlock(nb);
            if (front != null && front.equals(target))
                return new SenderHit(nb, nbRid);
            if (includeBack) {
                Block back = SignUtil.getBackBlock(nb);
                if (back != null && back.equals(target))
                    return new SenderHit(nb, nbRid);
            }
        }
        return null;
    }

    /* Delivery ------------------------------------------------------------------------- */

    /**
     * Attempts to deliver the items to the receiver's pipe network. Returns the leftover
     * items if anything was accepted, or null if nothing was (caller keeps its items).
     */
    List<ItemStack> sendThroughLink(Block senderSignBlock, UUID receiverId, List<ItemStack> items, Set<String> existingTeleports) {
        Long blockedUntil = deliveryBackoff.get(receiverId);
        if (blockedUntil != null) {
            if (System.currentTimeMillis() < blockedUntil)
                return null;
            deliveryBackoff.remove(receiverId);
        }

        PipeLinkIndex index = PipeLinkIndex.get();
        Block recvSign = index.getReceiverSignBlock(receiverId);
        if (recvSign == null)
            return null;
        if (!SignUtil.isSign(recvSign)) {
            // Stale index entry - the sign is gone.
            index.unregisterReceiver(receiverId);
            return null;
        }

        String key = PipeRequestEvent.buildTeleportKey(senderSignBlock, recvSign);
        if (existingTeleports != null && existingTeleports.contains(key)) {
            Bukkit.getLogger().warning("[Pipes] PipeLink loop detected: " + key);
            return null;
        }

        // Links can chain into each other; keep a hard depth limit so a badly built loop
        // degrades into a warning instead of a stack overflow.
        if (chainDepth >= MAX_CHAIN_DEPTH) {
            Bukkit.getLogger().warning("[Pipes] PipeLink chain deeper than " + MAX_CHAIN_DEPTH + " links, aborting at: " + key);
            return null;
        }

        chainDepth++;
        try {
            Block attached = SignUtil.getBackBlock(recvSign);
            List<Block> candidates = buildCandidates(attached, SignUtil.getBack(recvSign));

            // Try the candidate that worked last time first; injection points rarely change.
            int preferred = lastGoodCandidate.getOrDefault(receiverId, -1);
            if (preferred >= 0 && preferred < candidates.size()) {
                List<ItemStack> result = tryCandidate(candidates.get(preferred), attached, items, key, existingTeleports);
                if (result != null)
                    return result;
            }

            for (int i = 0; i < candidates.size(); i++) {
                if (i == preferred)
                    continue;
                List<ItemStack> result = tryCandidate(candidates.get(i), attached, items, key, existingTeleports);
                if (result != null) {
                    lastGoodCandidate.put(receiverId, i);
                    return result;
                }
            }
            deliveryBackoff.put(receiverId, System.currentTimeMillis() + RETRY_COOLDOWN_MILLIS);
            return null;
        } finally {
            chainDepth--;
        }
    }

    /** Fires one injection probe. Returns the leftovers if anything was accepted, else null. */
    private static List<ItemStack> tryCandidate(Block start, Block attached, List<ItemStack> items, String key, Set<String> existingTeleports) {
        List<ItemStack> probe = cloneStacks(items);
        PipeRequestEvent req = new PipeRequestEvent(start, probe, attached, true, 1);
        req.markTeleportUsed(key);
        if (existingTeleports != null) {
            for (String used : existingTeleports)
                req.markTeleportUsed(used);
        }
        Bukkit.getPluginManager().callEvent(req);
        return req.getItems().size() < items.size() ? req.getItems() : null;
    }

    private static List<Block> buildCandidates(Block attached, BlockFace backFace) {
        List<Block> ordered = new ArrayList<>(5);
        if (isPipeSegment(attached))
            ordered.add(attached);
        ordered.add(attached.getRelative(backFace));
        ordered.add(attached.getRelative(backFace, 2));
        ordered.add(attached.getRelative(rotateLeft(backFace)));
        ordered.add(attached.getRelative(rotateRight(backFace)));
        return ordered;
    }

    private static boolean isPipeSegment(Block b) {
        Material m = b.getType();
        return m == Material.GLASS || m == Material.TINTED_GLASS
                || m == Material.PISTON || m == Material.STICKY_PISTON
                || m.name().endsWith("_GLASS");
    }

    private static BlockFace rotateLeft(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case WEST -> BlockFace.SOUTH;
            case EAST -> BlockFace.NORTH;
            default -> f;
        };
    }

    private static BlockFace rotateRight(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            case EAST -> BlockFace.SOUTH;
            default -> f;
        };
    }

    private static List<ItemStack> cloneStacks(List<ItemStack> src) {
        List<ItemStack> out = new ArrayList<>(src.size());
        for (ItemStack s : src) {
            if (s != null)
                out.add(s.clone());
        }
        return out;
    }

    private record SenderHit(Block signBlock, UUID receiverId) {
    }
}
