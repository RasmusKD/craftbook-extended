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

    /**
     * Whether a delivery may load the receiver's chunk (one chunk, at the destination).
     * Off means links lie dormant while the receiver is unloaded - the old behaviour,
     * for servers that want strict chunk-neutrality at the cost of needing the far end
     * loaded before items flow.
     */
    private static volatile boolean loadReceiverChunk = true;

    public static void setLoadReceiverChunk(boolean load) {
        loadReceiverChunk = load;
    }

    /**
     * Minimum time between WAKING an unloaded receiver chunk, per receiver. Deliveries
     * to an already-loaded chunk are unthrottled; this only limits how often a link may
     * load a chunk that nobody else keeps loaded. Without it, a clocked sender is a
     * free chunk loader for the far end - farms and machines in that chunk would run
     * around the clock. With it, the chunk is awake a few seconds per window: items
     * still flow in batches, farms do not.
     */
    private static volatile long unloadedWakeCooldownMillis = 60_000L;

    public static void setUnloadedWakeCooldownSeconds(int seconds) {
        unloadedWakeCooldownMillis = Math.max(0, seconds) * 1000L;
    }

    /** Last wake time per CHUNK (not per receiver: several receivers in one chunk must
     * share the ration, or stacking them with staggered cooldowns would keep the chunk
     * permanently warm anyway). Bounded by a periodic clear. */
    private final Map<UUID, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap> chunkWakes = new ConcurrentHashMap<>();

    private static final int MAX_CHAIN_DEPTH = 16;
    private static int chainDepth = 0;

    /**
     * Link edges the current top-level operation is already traversing. The event-borne
     * teleport keys only follow request events, so put- and suck-driven loops (the
     * default topology - a pipe delivering into a sender sign) never carried them; this
     * set guards all three entry points uniformly. Single-threaded by Bukkit's event
     * dispatch, cleaned in finally.
     */
    private final Set<String> activeTeleports = new java.util.HashSet<>();

    /** Last warn time per message key, so a clocked loop cannot spam the log at pulse rate. */
    private final Map<String, Long> lastWarn = new java.util.HashMap<>();

    private void warnThrottled(String key, String message) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(key);
        if (last != null && now - last < 5000L)
            return;
        if (lastWarn.size() > 256)
            lastWarn.clear();
        lastWarn.put(key, now);
        Bukkit.getLogger().warning(message);
    }

    /** Last candidate index that accepted items, per receiver, so steady-state transfers probe once. */
    private final Map<UUID, Integer> lastGoodCandidate = new ConcurrentHashMap<>();

    /**
     * Drops the per-receiver caches for a retired receiver id. Without this, every
     * receiver sign ever broken leaves its UUID in these maps for the process lifetime.
     */
    void forgetReceiver(UUID rid) {
        lastGoodCandidate.remove(rid);
        deliveryBackoff.remove(rid);
    }

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
        // Extraction wish lists (MC1242) carry phantom items - routing them through a
        // link would materialise the wish at the receiver.
        if (e.isExtract())
            return;
        // includeBack matches onSuck: a sender sign mounted ON the piston must route
        // magnet/hop-driven requests the same way it routes redstone-driven pulls.
        SenderHit hit = findSender(e.getBlock(), true);
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

        int tx = target.getX(), ty = target.getY(), tz = target.getZ();

        UUID rid = index.getBoundReceiverAt(world, PipeLinkIndex.posKey(tx, ty, tz));
        if (rid != null && SignUtil.isSign(target))
            return verifiedHit(index, world, target, rid);

        // Probe the six neighbour positions by coordinate first. Every pipe event in a
        // world that has any sender used to allocate six Block objects here, almost
        // always to miss; now a Block is only materialised on an actual index hit.
        for (BlockFace face : NEIGHBOUR_FACES) {
            UUID nbRid = index.getBoundReceiverAt(world,
                    PipeLinkIndex.posKey(tx + face.getModX(), ty + face.getModY(), tz + face.getModZ()));
            if (nbRid == null)
                continue;
            Block nb = target.getRelative(face);
            if (!SignUtil.isSign(nb))
                continue;
            Block front = SignUtil.getFrontBlock(nb);
            if (front != null && front.equals(target))
                return verifiedHit(index, world, nb, nbRid);
            if (includeBack) {
                Block back = SignUtil.getBackBlock(nb);
                if (back != null && back.equals(target))
                    return verifiedHit(index, world, nb, nbRid);
            }
        }
        return null;
    }

    /**
     * Confirms an index hit against the sign's persistent data. Signs can vanish without a
     * break event (e.g. a wall sign popping off with its support block) and be replaced,
     * so stale entries are healed here instead of ghost-routing through a fresh sign.
     */
    private SenderHit verifiedHit(PipeLinkIndex index, UUID world, Block signBlock, UUID rid) {
        if (io.papermc.lib.PaperLib.getBlockState(signBlock, false).getState() instanceof org.bukkit.block.Sign sign
                && rid.equals(PipeLink.readBoundReceiverUUID(sign))) {
            return new SenderHit(signBlock, rid);
        }
        index.unbindSenderAt(world, signBlock.getX(), signBlock.getY(), signBlock.getZ());
        return null;
    }

    /* Delivery ------------------------------------------------------------------------- */

    /**
     * Attempts to deliver the items to the receiver's pipe network. Returns the leftover
     * items if anything was accepted, or null if nothing was (caller keeps its items).
     */
    List<ItemStack> sendThroughLink(Block senderSignBlock, UUID receiverId, List<ItemStack> items, Set<String> existingTeleports) {
        // Empty pulses reach here routinely (every redstone pulse on a piston facing
        // glass fires an empty suck event); without this guard each one probed up to
        // five candidates, causing real pulls in the receiver's network.
        if (items.isEmpty())
            return null;

        Long blockedUntil = deliveryBackoff.get(receiverId);
        if (blockedUntil != null) {
            if (System.currentTimeMillis() < blockedUntil)
                return null;
            deliveryBackoff.remove(receiverId);
        }

        PipeLinkIndex index = PipeLinkIndex.get();
        PipeLinkIndex.ReceiverRef ref = index.getReceiverRef(receiverId);
        if (ref != null) {
            org.bukkit.World w = Bukkit.getWorld(ref.world());
            if (w == null)
                return null;
            if (!w.isChunkLoaded(ref.x() >> 4, ref.z() >> 4)) {
                if (!loadReceiverChunk)
                    return null; // dormant while the receiver is unloaded
                // Waking an unloaded chunk is rationed per receiver, so a clocked
                // sender cannot function as a chunk loader for the far end.
                if (unloadedWakeCooldownMillis > 0) {
                    it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap wakes =
                            chunkWakes.computeIfAbsent(ref.world(), k -> new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap());
                    long chunkKey = ((long) (ref.x() >> 4) << 32) | ((ref.z() >> 4) & 0xFFFFFFFFL);
                    long now = System.currentTimeMillis();
                    long lastWake = wakes.get(chunkKey);
                    if (lastWake != 0L && now - lastWake < unloadedWakeCooldownMillis)
                        return null;
                    if (wakes.size() > 4096)
                        wakes.clear();
                    wakes.put(chunkKey, now);
                }
            }
        }
        Block recvSign = index.getReceiverSignBlock(receiverId);
        if (recvSign == null) {
            // Boot gap: the index only knows chunks that have loaded this session. The
            // sender's own PDC carries the receiver's position, so resolve from there -
            // this is what makes a link as durable as two adjacent pipe blocks.
            recvSign = resolveFromSenderPos(index, senderSignBlock, receiverId);
            if (recvSign == null)
                return null;
        }
        if (!SignUtil.isSign(recvSign)
                || !(io.papermc.lib.PaperLib.getBlockState(recvSign, false).getState() instanceof org.bukkit.block.Sign recvState)
                || !receiverId.equals(PipeLink.readReceiverUUID(recvState))) {
            // Stale index entry - drop the CACHE entry only. unregisterReceiver would
            // also erase SEND_BOUND from every sender's PDC, letting one failed read
            // of the receiver sign permanently unbind a whole build; the destructive
            // form is reserved for a player explicitly breaking the sign.
            index.forgetReceiverEntry(receiverId);
            return null;
        }

        // Backfill the stored position on bindings made before it existed, so they too
        // become reboot-proof the first time they deliver.
        if (io.papermc.lib.PaperLib.getBlockState(senderSignBlock, false).getState() instanceof org.bukkit.block.Sign sSign
                && !PipeLink.hasBoundReceiverPos(sSign)) {
            PipeLink.writeBoundReceiverPos(sSign, recvSign.getWorld().getUID(), recvSign.getX(), recvSign.getY(), recvSign.getZ());
        }

        if (!PipeLinkProtection.isCrossLinkAllowed(senderSignBlock.getWorld(), recvSign.getWorld())) {
            return null; // cross-world links lie dormant while not permitted
        }

        if (!PipeLinkProtection.isWorldAllowed(senderSignBlock.getWorld()) || !PipeLinkProtection.isWorldAllowed(recvSign.getWorld())) {
            return null; // links touching a blacklisted world lie dormant
        }

        String key = PipeRequestEvent.buildTeleportKey(senderSignBlock, recvSign);
        if (existingTeleports != null && existingTeleports.contains(key)) {
            warnThrottled(key, "[Pipes] PipeLink loop detected: " + key);
            deliveryBackoff.put(receiverId, System.currentTimeMillis() + RETRY_COOLDOWN_MILLIS);
            return null;
        }

        // Links can chain into each other; keep a hard depth limit so a badly built loop
        // degrades into a warning instead of a stack overflow.
        if (chainDepth >= MAX_CHAIN_DEPTH) {
            warnThrottled("depth:" + key, "[Pipes] PipeLink chain deeper than " + MAX_CHAIN_DEPTH + " links, aborting at: " + key);
            return null;
        }

        if (!activeTeleports.add(key)) {
            // This edge is already being traversed by the running operation: a put- or
            // suck-driven loop the event-borne keys cannot see.
            warnThrottled(key, "[Pipes] PipeLink loop detected: " + key);
            deliveryBackoff.put(receiverId, System.currentTimeMillis() + RETRY_COOLDOWN_MILLIS);
            return null;
        }

        chainDepth++;
        try {
            Block attached = SignUtil.getBackBlock(recvSign);
            List<Block> candidates = buildCandidates(attached, SignUtil.getBack(recvSign));

            // Try the candidate that worked last time first; injection points rarely change.
            int preferred = lastGoodCandidate.getOrDefault(receiverId, -1);
            if (preferred >= 0 && preferred < candidates.size() && candidates.get(preferred) != null) {
                List<ItemStack> result = tryCandidate(candidates.get(preferred), attached, items, key, existingTeleports);
                if (result != null)
                    return result;
            }

            for (int i = 0; i < candidates.size(); i++) {
                if (i == preferred || candidates.get(i) == null)
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
            activeTeleports.remove(key);
        }
    }

    /**
     * Resolves a receiver the index has never seen this session, using the position the
     * sender sign stored at bind time. Loads that one chunk, verifies the receiver's
     * identity by PDC, and re-registers it. If the loaded block verifiably is not the
     * receiver any more, the link is factually dead (the sign is gone), and the sender
     * is unbound - same semantics as breaking the receiver sign.
     */
    private Block resolveFromSenderPos(PipeLinkIndex index, Block senderSignBlock, UUID receiverId) {
        if (!(io.papermc.lib.PaperLib.getBlockState(senderSignBlock, false).getState() instanceof org.bukkit.block.Sign senderSign))
            return null;
        Object[] pos = PipeLink.readBoundReceiverPos(senderSign);
        if (pos == null)
            return null; // pre-position binding; heals when the receiver chunk loads
        org.bukkit.World w = Bukkit.getWorld((UUID) pos[0]);
        if (w == null)
            return null;
        int x = (Integer) pos[1], y = (Integer) pos[2], z = (Integer) pos[3];
        if (!loadReceiverChunk && !w.isChunkLoaded(x >> 4, z >> 4))
            return null;
        Block candidate = w.getBlockAt(x, y, z);
        if (io.papermc.lib.PaperLib.getBlockState(candidate, false).getState() instanceof org.bukkit.block.Sign recvState
                && receiverId.equals(PipeLink.readReceiverUUID(recvState))) {
            index.registerReceiver(receiverId, w.getUID(), x, y, z);
            return candidate;
        }
        // Verified gone: the position loaded and holds no receiver with this identity.
        index.unbindSenderAt(senderSignBlock.getWorld().getUID(), senderSignBlock.getX(), senderSignBlock.getY(), senderSignBlock.getZ());
        PipeLink.clearBoundReceiverUUID(senderSign);
        return null;
    }

    /** Fires one injection probe. Returns the leftovers if anything was accepted, else null. */
    private static List<ItemStack> tryCandidate(Block start, Block attached, List<ItemStack> items, String key, Set<String> existingTeleports) {
        List<ItemStack> probe = cloneStacks(items);
        int before = totalAmount(probe);
        PipeRequestEvent req = new PipeRequestEvent(start, probe, attached, true, 1);
        req.markTeleportUsed(key);
        if (existingTeleports != null) {
            for (String used : existingTeleports)
                req.markTeleportUsed(used);
        }
        Bukkit.getPluginManager().callEvent(req);
        // Compare item quantities, not stack counts: a partial fill leaves the list the
        // same length with a reduced amount, and missing it here would re-probe the next
        // candidate with a full fresh clone - duplicating what was already delivered.
        return totalAmount(req.getItems()) < before ? req.getItems() : null;
    }

    private static int totalAmount(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack s : stacks) {
            if (s != null)
                total += s.getAmount();
        }
        return total;
    }

    // Always five entries in a fixed order, so a remembered lastGoodCandidate index
    // keeps meaning the same position. The list used to include `attached` only when it
    // was a pipe segment, so placing or breaking glass at the receiver shifted every
    // index by one and the cache silently started probing the wrong block first.
    // A null entry marks a position that is not currently probeable.
    private static List<Block> buildCandidates(Block attached, BlockFace backFace) {
        List<Block> ordered = new ArrayList<>(5);
        ordered.add(isPipeSegment(attached) ? attached : null);
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
