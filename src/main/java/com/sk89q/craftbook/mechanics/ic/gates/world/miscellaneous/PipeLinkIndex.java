package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.util.SignUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory spatial index of all PipeLink signs, so pipe events can be routed with cheap
 * hash lookups instead of reading sign state off the world.
 *
 * The sign PersistentDataContainers remain the source of truth; this index is rebuilt from
 * them as chunks load (and once at startup for already-loaded chunks), and kept in sync by
 * the bind and break listeners and the IC lifecycle.
 */
public class PipeLinkIndex implements Listener {

    private static final PipeLinkIndex INSTANCE = new PipeLinkIndex();

    /** Sender sign position (packed) per world -> bound receiver id. Primitive-keyed:
     * the router probes seven positions per pipe event, and a boxed Long per probe was
     * the same allocation the traversal caches already had removed. Main-thread only. */
    private final Map<UUID, Long2ObjectOpenHashMap<UUID>> sendersByWorld = new ConcurrentHashMap<>();
    /** Receiver id -> receiver sign location. */
    private final Map<UUID, ReceiverRef> receivers = new ConcurrentHashMap<>();
    /** Receiver id -> known bound sender sign locations, for inspection and cleanup. */
    private final Map<UUID, Set<SenderRef>> recvToSenders = new ConcurrentHashMap<>();

    public static PipeLinkIndex get() {
        return INSTANCE;
    }

    public static long posKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
    }

    public static long posKey(Block block) {
        return posKey(block.getX(), block.getY(), block.getZ());
    }

    /* Lookups -------------------------------------------------------------------------- */

    /** Returns the receiver id a sender sign at this position is bound to, or null. */
    public UUID getBoundReceiverAt(UUID world, long posKey) {
        Long2ObjectOpenHashMap<UUID> worldSenders = sendersByWorld.get(world);
        return worldSenders == null ? null : worldSenders.get(posKey);
    }

    /** True if this world has no sender signs at all - the common fast path. */
    public boolean hasNoSenders(UUID world) {
        Long2ObjectOpenHashMap<UUID> worldSenders = sendersByWorld.get(world);
        return worldSenders == null || worldSenders.isEmpty();
    }

    public ReceiverRef getReceiverRef(UUID rid) {
        return receivers.get(rid);
    }

    public Block getReceiverSignBlock(UUID rid) {
        ReceiverRef ref = receivers.get(rid);
        if (ref == null)
            return null;
        World w = Bukkit.getWorld(ref.world());
        return w == null ? null : w.getBlockAt(ref.x(), ref.y(), ref.z());
    }

    public Set<SenderRef> getSendersFor(UUID rid) {
        Set<SenderRef> senders = recvToSenders.get(rid);
        return senders == null ? new HashSet<>() : new HashSet<>(senders);
    }

    /* Mutation ------------------------------------------------------------------------- */

    public void registerReceiver(UUID rid, UUID world, int x, int y, int z) {
        receivers.put(rid, new ReceiverRef(world, x, y, z));
    }

    public void unregisterReceiver(UUID rid) {
        Set<SenderRef> senders = recvToSenders.remove(rid);
        if (senders != null) {
            for (SenderRef s : senders) {
                PipeLink.clearSenderUUIDAt(s.world(), s.x(), s.y(), s.z());
                removeSenderEntry(s.world(), posKey(s.x(), s.y(), s.z()));
            }
        }
        receivers.remove(rid);
        PipeLinkRouter.get().forgetReceiver(rid);
    }

    /**
     * Cache-only removal: drops the receiver from the index and the router's caches
     * WITHOUT touching any sign PDC. Used by the router's stale-entry heal, where the
     * source of truth must survive a transient read failure; the destructive
     * {@link #unregisterReceiver} is reserved for a player explicitly breaking the sign.
     */
    public void forgetReceiverEntry(UUID rid) {
        receivers.remove(rid);
        PipeLinkRouter.get().forgetReceiver(rid);
    }

    public void bindSender(UUID world, int x, int y, int z, UUID rid) {
        long key = posKey(x, y, z);
        UUID prev = sendersByWorld.computeIfAbsent(world, w -> new Long2ObjectOpenHashMap<>()).put(key, rid);
        SenderRef ref = new SenderRef(world, x, y, z);
        if (prev != null && !prev.equals(rid)) {
            Set<SenderRef> set = recvToSenders.get(prev);
            if (set != null)
                set.remove(ref);
        }
        recvToSenders.computeIfAbsent(rid, r -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(ref);
    }

    public void unbindSenderAt(UUID world, int x, int y, int z) {
        long key = posKey(x, y, z);
        UUID rid = removeSenderEntry(world, key);
        if (rid != null) {
            Set<SenderRef> set = recvToSenders.get(rid);
            if (set != null)
                set.remove(new SenderRef(world, x, y, z));
        }
    }

    private UUID removeSenderEntry(UUID world, long key) {
        Long2ObjectOpenHashMap<UUID> worldSenders = sendersByWorld.get(world);
        return worldSenders == null ? null : worldSenders.remove(key);
    }

    public void cleanup() {
        sendersByWorld.clear();
        receivers.clear();
        recvToSenders.clear();
    }

    /* Chunk scanning ------------------------------------------------------------------- */

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(org.bukkit.event.block.SignChangeEvent event) {
        // A freshly placed sign carries no PDC, so any stale sender entry at this position
        // must go; an edited existing sign keeps its PDC and is simply re-indexed. The
        // state is read next tick, after the change has been applied.
        org.bukkit.block.Block block = event.getBlock();
        Bukkit.getScheduler().runTask(com.sk89q.craftbook.bukkit.CraftBookPlugin.inst(), () -> {
            UUID world = block.getWorld().getUID();
            if (block.getState() instanceof Sign sign) {
                if (PipeLink.readBoundReceiverUUID(sign) == null)
                    unbindSenderAt(world, block.getX(), block.getY(), block.getZ());
                indexSign(sign);
            } else {
                unbindSenderAt(world, block.getX(), block.getY(), block.getZ());
            }
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        scanChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        evictChunk(event.getChunk());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        sendersByWorld.remove(world);
        receivers.entrySet().removeIf(e -> e.getValue().world().equals(world));
        for (Set<SenderRef> set : recvToSenders.values())
            set.removeIf(s -> s.world().equals(world));
    }

    /** Indexes every PipeLink sign already present in loaded chunks. Called on enable. */
    public void scanAllLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunk(chunk);
            }
        }
    }

    private static final boolean PAPER = PaperLib.isPaper();

    private void scanChunk(Chunk chunk) {
        if (PAPER) {
            // Paper can filter to signs before any state is created, without snapshots.
            for (BlockState state : chunk.getTileEntities(SignUtil::isSign, false)) {
                if (state instanceof Sign sign)
                    indexSign(sign);
            }
        } else {
            for (BlockState state : chunk.getTileEntities()) {
                if (state instanceof Sign sign)
                    indexSign(sign);
            }
        }
    }

    private void indexSign(Sign sign) {
        UUID rid = PipeLink.readReceiverUUID(sign);
        if (rid != null) {
            if (!PipeLink.isReceiverEchoValid(sign)) {
                // Cloned PDC (WorldEdit paste, structure block): mint a fresh identity
                // so the copy cannot hijack the original's bound senders.
                rid = UUID.randomUUID();
                PipeLink.writeReceiverUUID(sign, rid);
            } else if (!PipeLink.hasReceiverEcho(sign)) {
                // Legacy binding from before the echo existed; backfill it.
                PipeLink.writeReceiverUUID(sign, rid);
            }
            registerReceiver(rid, sign.getWorld().getUID(), sign.getX(), sign.getY(), sign.getZ());
        }
        UUID bound = PipeLink.readBoundReceiverUUID(sign);
        if (bound != null) {
            bindSender(sign.getWorld().getUID(), sign.getX(), sign.getY(), sign.getZ(), bound);
        }
    }

    private void evictChunk(Chunk chunk) {
        UUID world = chunk.getWorld().getUID();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        Long2ObjectOpenHashMap<UUID> worldSenders = sendersByWorld.get(world);
        if (worldSenders != null && !worldSenders.isEmpty()) {
            worldSenders.long2ObjectEntrySet().removeIf(e -> {
                long key = e.getLongKey();
                if (unpackX(key) >> 4 != cx || unpackZ(key) >> 4 != cz)
                    return false;
                Set<SenderRef> set = recvToSenders.get(e.getValue());
                if (set != null)
                    set.removeIf(s -> posKey(s.x(), s.y(), s.z()) == key && s.world().equals(world));
                return true;
            });
        }

        receivers.entrySet().removeIf(e -> {
            ReceiverRef ref = e.getValue();
            if (!ref.world().equals(world) || ref.x() >> 4 != cx || ref.z() >> 4 != cz)
                return false;
            // Router caches repopulate on next use; dropping them here keeps them
            // bounded by loaded receivers instead of lifetime receiver churn.
            PipeLinkRouter.get().forgetReceiver(e.getKey());
            return true;
        });
    }

    private static int unpackX(long key) {
        return (int) (key >> 38);
    }

    private static int unpackZ(long key) {
        return (int) (key << 26 >> 38);
    }

    public record ReceiverRef(UUID world, int x, int y, int z) {
    }

    public record SenderRef(UUID world, int x, int y, int z) {
    }
}
