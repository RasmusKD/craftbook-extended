package com.sk89q.craftbook.mechanics.pipe;

import com.sk89q.craftbook.AbstractCraftBookMechanic;
import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.CraftBookPlayer;
import com.sk89q.craftbook.bukkit.CraftBookPlugin;
import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import com.sk89q.craftbook.util.BlockSyntax;
import com.sk89q.craftbook.util.BlockUtil;
import com.sk89q.craftbook.util.EventUtil;
import com.sk89q.craftbook.util.InventoryUtil;
import com.sk89q.craftbook.util.ItemSyntax;
import com.sk89q.craftbook.util.ItemUtil;
import com.sk89q.craftbook.util.LocationUtil;
import com.sk89q.craftbook.util.ProtectionUtil;
import com.sk89q.craftbook.util.RegexUtil;
import com.sk89q.craftbook.util.SignUtil;
import com.sk89q.craftbook.util.VerifyUtil;
import com.sk89q.craftbook.util.events.SourcedBlockRedstoneEvent;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dropper;
import org.bukkit.block.Furnace;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Crafter;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Piston;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Pipes extends AbstractCraftBookMechanic {

    @EventHandler(priority = EventPriority.HIGH)
    public void onSignChange(SignChangeEvent event) {

        if(!EventUtil.passesFilter(event)) return;

        // Any sign edit near a pipe may change its filters.
        invalidateFilterCacheAround(event.getBlock());

        if(!event.getLine(1).equalsIgnoreCase("[pipe]")) return;

        CraftBookPlayer player = CraftBookPlugin.inst().wrapPlayer(event.getPlayer());

        if(!player.hasPermission("craftbook.circuits.pipes")) {
            if(CraftBookPlugin.inst().getConfiguration().showPermissionMessages)
                player.printError("mech.create-permission");
            SignUtil.cancelSign(event);
            return;
        }

        if(ProtectionUtil.shouldUseProtection()) {
            Block pistonBlock = null;

            if (SignUtil.isWallSign(event.getBlock())) {
                pistonBlock = SignUtil.getBackBlock(event.getBlock());
            } else if (SignUtil.isStandingSign(event.getBlock())) {
                if (isPiston(event.getBlock().getRelative(BlockFace.DOWN))) {
                    pistonBlock = event.getBlock().getRelative(BlockFace.DOWN);
                } else if (isPiston(event.getBlock().getRelative(BlockFace.UP))) {
                    pistonBlock = event.getBlock().getRelative(BlockFace.UP);
                }
            }
            if(pistonBlock != null && isPiston(pistonBlock)) {
                Piston pis = (Piston) pistonBlock.getBlockData();
                Block off = pistonBlock.getRelative(pis.getFacing());
                if (InventoryUtil.doesBlockHaveInventory(off)) {
                    if (!ProtectionUtil.canAccessInventory(event.getPlayer(), off)) {
                        if (CraftBookPlugin.inst().getConfiguration().showPermissionMessages)
                            player.printError("area.use-permission");
                        SignUtil.cancelSign(event);
                        return;
                    }
                }
            } else {
                player.printError("circuits.pipes.pipe-not-found");
                SignUtil.cancelSign(event);
                return;
            }
        }

        event.setLine(1, "[Pipe]");
        player.print("circuits.pipes.create");

        // The pull-claim rule blocks at pulse time, where no player is around to tell -
        // so warn the creator here if this pipe's pulls would be blocked as things stand.
        Block pullPiston = null;
        if (SignUtil.isWallSign(event.getBlock())) {
            pullPiston = SignUtil.getBackBlock(event.getBlock());
        } else if (SignUtil.isStandingSign(event.getBlock())) {
            if (isPiston(event.getBlock().getRelative(BlockFace.DOWN))) {
                pullPiston = event.getBlock().getRelative(BlockFace.DOWN);
            } else if (isPiston(event.getBlock().getRelative(BlockFace.UP))) {
                pullPiston = event.getBlock().getRelative(BlockFace.UP);
            }
        }
        if (pullPiston != null && pullPiston.getType() == Material.STICKY_PISTON) {
            Piston pis = (Piston) pullPiston.getBlockData();
            Block off = pullPiston.getRelative(pis.getFacing());
            if (InventoryUtil.doesBlockHaveInventory(off)) {
                String pullDenial = com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous.PipeLinkProtection
                        .describePullDenial(pullPiston, off);
                if (pullDenial != null) {
                    event.getPlayer().sendMessage(org.bukkit.ChatColor.YELLOW
                            + "[Pipes] Advarsel: pipen er lavet, men den kan ikke suge fra containeren — " + pullDenial + ".");
                }
            }
        }
    }

    private static boolean isPiston(Block block) {
        Material type = block.getType();
        return type == Material.PISTON || type == Material.STICKY_PISTON;
    }

    private static ChangedSign getSignOnPiston(Block block) {
        BlockData blockData = block.getBlockData();
        BlockFace facing = BlockFace.SELF;
        if(blockData instanceof Directional directional) {
            facing = directional.getFacing();
        }

        for(BlockFace face : LocationUtil.getDirectFaces()) {
            if(face == facing || !SignUtil.isSign(block.getRelative(face)))
                continue;
            if(!SignUtil.isStandingSign(block.getRelative(face)) && (face == BlockFace.UP || face == BlockFace.DOWN))
                continue;
            else if (SignUtil.isStandingSign(block.getRelative(face)) && face != BlockFace.UP && face != BlockFace.DOWN)
                continue;
            if(!SignUtil.isStandingSign(block.getRelative(face)) && !SignUtil.getBackBlock(block.getRelative(face)).getLocation().equals(block.getLocation()))
                continue;
            ChangedSign sign = CraftBookBukkitUtil.toChangedSign(block.getRelative(face));
            if(sign != null && sign.getLine(1).equalsIgnoreCase("[Pipe]"))
                return sign;
        }

        return null;
    }

    /* Filter caching ------------------------------------------------------------------- */

    /**
     * Parsed filter lines of the [Pipe] sign attached to a piston or dropper. Parsing sign
     * text involves several block state lookups and item syntax parsing per block per
     * traversal, so results are cached and invalidated when nearby blocks or signs change.
     */
    private static final class CachedFilters {
        final HashSet<ItemStack> filters;
        final HashSet<ItemStack> exceptions;
        final boolean passThrough;
        final boolean hasSign;
        final long parseTime = System.currentTimeMillis();

        CachedFilters(HashSet<ItemStack> filters, HashSet<ItemStack> exceptions, boolean passThrough, boolean hasSign) {
            this.filters = filters;
            this.exceptions = exceptions;
            this.passThrough = passThrough;
            this.hasSign = hasSign;
        }

        boolean isStale() {
            return System.currentTimeMillis() - parseTime > CACHE_TTL_MILLIS;
        }
    }

    // Short TTL: the cache only needs to collapse the per-pulse cost of clocked
    // pistons; a long window lets eventless sign changes (WorldEdit, plugin API)
    // mis-route items rather than merely delay a reparse.
    private static final long CACHE_TTL_MILLIS = 10 * 1000L;

    // The filter cache, the round-robin cursor and the full-pipe pause all live in the
    // per-world holder below, keyed by packed position rather than by Location.

    private CachedFilters getFilters(Block block) {
        if (!pipeFilterCache)
            return parseFilters(block);

        Long2ObjectOpenHashMap<CachedFilters> cache = caches(block.getWorld()).filters;
        long key = posKey(block);
        CachedFilters cached = cache.get(key);
        if (cached == null || cached.isStale()) {
            cached = parseFilters(block);
            cache.put(key, cached);
        }
        return cached;
    }

    private static boolean isPassThroughMarker(String line0) {
        String token = line0.trim().toLowerCase(java.util.Locale.ROOT);
        return token.equals("pass") || token.equals("passthrough") || token.equals("bypass") || token.equals("b");
    }

    private static CachedFilters parseFilters(Block block) {
        ChangedSign sign = getSignOnPiston(block);

        HashSet<ItemStack> filters = new HashSet<>();
        HashSet<ItemStack> exceptions = new HashSet<>();
        boolean passThrough = sign != null && isPassThroughMarker(sign.getLine(0));

        if(sign != null) {
            for(String line3 : RegexUtil.COMMA_PATTERN.split(sign.getLine(2))) {
                filters.add(ItemSyntax.getItem(line3.trim()));
            }
            for(String line4 : RegexUtil.COMMA_PATTERN.split(sign.getLine(3))) {
                exceptions.add(ItemSyntax.getItem(line4.trim()));
            }

            filters.removeAll(Collections.<ItemStack>singleton(null));
            exceptions.removeAll(Collections.<ItemStack>singleton(null));
        }

        return new CachedFilters(filters, exceptions, passThrough, sign != null);
    }

    /**
     * Drops the per-piston round-robin cursor and pause window for a block that is going
     * away, so a new piston at the same position does not inherit either.
     */
    private void forgetPistonState(Block block) {
        WorldTypes wt = typeCache.get(block.getWorld().getUID());
        if (wt == null)
            return;
        long key = posKey(block);
        wt.pullCursor.remove(key);
        wt.fullBackoff.remove(key);
        wt.smokeAt.remove(key);
        wt.blockedPistons.remove(key);
    }

    private void invalidateFilterCacheAround(Block center) {
        if (!pipeFilterCache)
            return;
        WorldTypes wt = typeCache.get(center.getWorld().getUID());
        if (wt == null || wt.filters.isEmpty())
            return;
        // Signs attach to their block, so anything parsed from a sign within one block
        // of the change may now be outdated.
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    wt.filters.remove(posKey(cx + x, cy + y, cz + z));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        invalidateFilterCacheAround(event.getBlock());
        invalidateTypeCacheAt(event.getBlock());
        // A new piston at the same spot must not inherit the old one's round-robin
        // cursor or pause window; removing absent keys is a no-op.
        forgetPistonState(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        invalidateFilterCacheAround(event.getBlock());
        invalidateTypeCacheAt(event.getBlock());
    }

    // Bulk block changes invalidate exactly the touched positions in BOTH caches.
    // The old whole-world type-cache wipe made piston-heavy servers pay full reparse
    // constantly, while the filter cache was not invalidated at all - letting a sign
    // destroyed by TNT or moved by a piston keep filtering (and keep satisfying
    // require-sign) for the whole TTL.
    private void invalidateBothCachesAt(Block block) {
        invalidateTypeCacheAt(block);
        invalidateFilterCacheAround(block);
        // A retracted piston is pushable, so a pipe's sticky piston can be relocated
        // by another piston with no BlockBreakEvent; drop its per-piston state too.
        forgetPistonState(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        invalidateBothCachesAt(event.getBlock());
        for (Block b : event.blockList())
            invalidateBothCachesAt(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList())
            invalidateBothCachesAt(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        invalidateBothCachesAt(event.getBlock());
        for (Block b : event.getBlocks()) {
            invalidateBothCachesAt(b);
            invalidateBothCachesAt(b.getRelative(event.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        invalidateBothCachesAt(event.getBlock());
        for (Block b : event.getBlocks()) {
            invalidateBothCachesAt(b);
            invalidateBothCachesAt(b.getRelative(event.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        invalidateBothCachesAt(event.getBlock());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        // One holder per world now carries every position-keyed cache.
        typeCache.remove(event.getWorld().getUID());
    }

    /* Traversal ------------------------------------------------------------------------ */

    private static long posKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | (long) y & 0xFFFL;
    }

    private static long posKey(Block block) {
        return posKey(block.getX(), block.getY(), block.getZ());
    }

    /* Block type memoisation ------------------------------------------------------------
     *
     * The traversal re-reads the same block types on every pulse. They are memoised here
     * so repeat pulses run on hash lookups. Player block changes invalidate precisely via
     * events; bulk changes (explosions, pistons) drop the world's cache; and the whole
     * cache expires every few seconds as a safety net for eventless changes (WorldEdit,
     * plugin API). Anything actually deposited into is still verified live. */

    private static final long TYPE_CACHE_TTL_MILLIS = 5000L;

    // Primitive-keyed: a boxed Long per lookup was allocated for every block visited on
    // every pulse, and Long.valueOf only caches -128..127 so real coordinates always
    // allocate. Single-threaded (Bukkit event dispatch), hence plain fastutil maps.
    private static final class WorldTypes {
        final Long2ObjectOpenHashMap<Material> types = new Long2ObjectOpenHashMap<>();
        final Long2ByteOpenHashMap insulators = new Long2ByteOpenHashMap();
        // Position-keyed alongside the type memoisation so none of them need a Location
        // object as a key: getFilters runs once per piston per visit, so a large network
        // used to allocate one Location per piston per pulse just to look itself up.
        final Long2ObjectOpenHashMap<CachedFilters> filters = new Long2ObjectOpenHashMap<>();
        final Long2IntOpenHashMap pullCursor = new Long2IntOpenHashMap();
        final Long2LongOpenHashMap fullBackoff = new Long2LongOpenHashMap();
        final Long2LongOpenHashMap smokeAt = new Long2LongOpenHashMap();
        /** Pistons whose last completed pull was a total failure; kept smoking by the task. */
        final LongOpenHashSet blockedPistons = new LongOpenHashSet();
        long clearedAt = System.currentTimeMillis();

        WorldTypes() {
            insulators.defaultReturnValue((byte) -1);
            fullBackoff.defaultReturnValue(0L);
        }
    }

    private final Map<UUID, WorldTypes> typeCache = new ConcurrentHashMap<>();

    /** Per-world caches, without the TTL sweep (which only applies to the type memoisation). */
    private WorldTypes caches(World world) {
        return typeCache.computeIfAbsent(world.getUID(), w -> new WorldTypes());
    }

    private WorldTypes worldTypes(World world) {
        WorldTypes wt = caches(world);
        long now = System.currentTimeMillis();
        if (now - wt.clearedAt > TYPE_CACHE_TTL_MILLIS) {
            wt.types.clear();
            wt.insulators.clear();
            wt.clearedAt = now;
        }
        return wt;
    }

    private Material typeOf(Block block) {
        if (!pipeTraversalCache)
            return block.getType();
        Long2ObjectOpenHashMap<Material> types = worldTypes(block.getWorld()).types;
        long key = posKey(block);
        Material cached = types.get(key);
        if (cached != null)
            return cached;
        Material type = block.getType();
        types.put(key, type);
        return type;
    }

    private boolean isInsulator(Block block) {
        if (!pipeTraversalCache)
            return pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(block.getBlockData()));
        Long2ByteOpenHashMap insulators = worldTypes(block.getWorld()).insulators;
        long key = posKey(block);
        byte cached = insulators.get(key); // -1 when absent
        if (cached >= 0)
            return cached == 1;
        boolean result = pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(block.getBlockData()));
        insulators.put(key, (byte) (result ? 1 : 0));
        return result;
    }

    private void invalidateTypeCacheAt(Block block) {
        WorldTypes wt = typeCache.get(block.getWorld().getUID());
        if (wt == null)
            return;
        long key = posKey(block);
        wt.types.remove(key);
        wt.insulators.remove(key);
    }

    private void clearTypeCache(World world) {
        WorldTypes wt = typeCache.get(world.getUID());
        if (wt != null) {
            wt.types.clear();
            wt.insulators.clear();
        }
    }

    private void searchNearbyPipes(Block block, LongOpenHashSet visitedPipes, List<ItemStack> items, boolean runPassThrough) {
        Deque<Block> searchQueue = new ArrayDeque<>();
        searchQueue.addFirst(block);

        //Use the queue to search blocks.
        while (!searchQueue.isEmpty()) {
            Block bl = searchQueue.poll();
            Material blType = typeOf(bl);
            if (blType == Material.PISTON) {
                CachedFilters signFilters = getFilters(bl);
                // A [Pipe] sign marked 'pass'/'bypass'/'b' turns this piston into a plain
                // conduit: nothing is deposited here and items flow through untouched.
                if (!signFilters.passThrough && !processPistonOutput(bl, items, signFilters, runPassThrough))
                    continue;
            } else if (blType == Material.DROPPER) {
                CachedFilters signFilters = getFilters(bl);
                if (!signFilters.passThrough && !processDropperOutput(bl, items, signFilters, runPassThrough))
                    continue;
            }

            if (!items.isEmpty()) {
                if (!pipesDiagonal) {
                    // Only the six direct faces can connect; skip the full 27-neighbour scan.
                    visitedPipes.add(posKey(bl));
                    for (int[] d : DIRECT_NEIGHBOURS) {
                        if (items.isEmpty())
                            return;
                        expandNeighbour(bl, blType, d[0], d[1], d[2], visitedPipes, searchQueue);
                    }
                } else {
                    // Mark the current block visited first, or the (0,0,0) offset below
                    // re-queues it and the whole 27-neighbour pass runs twice.
                    visitedPipes.add(posKey(bl));
                    //Enumerate the search queue.
                    for (int x = -1; x < 2; x++) {
                        for (int y = -1; y < 2; y++) {
                            for (int z = -1; z < 2; z++) {

                                if(items.isEmpty())
                                    return;

                                boolean xIsY = Math.abs(x) == Math.abs(y);
                                boolean xIsZ = Math.abs(x) == Math.abs(z);
                                if (xIsY && xIsZ) {
                                    if (isInsulator(bl.getRelative(x, 0, 0))
                                            && isInsulator(bl.getRelative(0, y, 0))
                                            && isInsulator(bl.getRelative(0, 0, z))) {
                                        continue;
                                    }
                                } else if (xIsY) {
                                    if (isInsulator(bl.getRelative(x, 0, 0))
                                            && isInsulator(bl.getRelative(0, y, 0))) {
                                        continue;
                                    }
                                } else if (xIsZ) {
                                    if (isInsulator(bl.getRelative(x, 0, 0))
                                            && isInsulator(bl.getRelative(0, 0, z))) {
                                        continue;
                                    }
                                } else {
                                    if (isInsulator(bl.getRelative(0, y, 0))
                                            && isInsulator(bl.getRelative(0, 0, z))) {
                                        continue;
                                    }
                                }

                                expandNeighbour(bl, blType, x, y, z, visitedPipes, searchQueue);
                            }
                        }
                    }
                }
            }
        }
    }

    private static final int[][] DIRECT_NEIGHBOURS = {
        {-1, 0, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {0, 1, 0}, {1, 0, 0}
    };

    private void expandNeighbour(Block bl, Material blType, int x, int y, int z, LongOpenHashSet visitedPipes, Deque<Block> searchQueue) {
        Block off = bl.getRelative(x, y, z);
        Material offType = typeOf(off);

        if (!isValidPipeBlock(offType)) return;

        if (!visitedPipes.add(posKey(off))) return;

        if(ItemUtil.isStainedGlass(blType) && ItemUtil.isStainedGlass(offType) && blType != offType) return;

        if(offType == Material.GLASS || ItemUtil.isStainedGlass(offType)) {
            searchQueue.add(off);
        } else if (offType == Material.GLASS_PANE || ItemUtil.isStainedGlassPane(offType)) {
            Block offsetBlock = off.getRelative(x, y, z);
            Material offsetBlockType = typeOf(offsetBlock);
            if (!isValidPipeBlock(offsetBlockType)) return;
            if (visitedPipes.contains(posKey(offsetBlock))) return;
            if(ItemUtil.isStainedGlassPane(offType)) {
                if((ItemUtil.isStainedGlass(blType)
                        || ItemUtil.isStainedGlassPane(blType)) && ItemUtil.getStainedColor(offType) != ItemUtil
                        .getStainedColor(offsetBlockType)
                        || (ItemUtil.isStainedGlass(offsetBlockType)
                        || ItemUtil.isStainedGlassPane(offsetBlockType)) && ItemUtil.getStainedColor(offType) != ItemUtil
                        .getStainedColor(offsetBlockType)) return;
            }
            visitedPipes.add(posKey(offsetBlock));
            searchQueue.add(off.getRelative(x, y, z));
        } else if(offType == Material.PISTON)
            searchQueue.addFirst(off); //Pistons are treated with higher priority.
    }

    /**
     * Deposits matching items into the container this piston faces. Returns false when the
     * search should end at this piston (vanilla behaviour when its filters match nothing),
     * true when the remaining items should continue past it.
     */
    private boolean processPistonOutput(Block bl, List<ItemStack> items, CachedFilters signFilters, boolean runPassThrough) {
        // The block data is read live, so a stale type cache can never mis-deposit.
        if (!(bl.getBlockData() instanceof Piston p))
            return true;

        List<ItemStack> filteredItems = new ArrayList<>(VerifyUtil.withoutNulls(ItemUtil.filterItemsLoose(items, signFilters.filters, signFilters.exceptions)));

        // Skip the event (and the defensive set copies) entirely when nothing listens.
        if (PipeFilterEvent.getHandlerList().getRegisteredListeners().length > 0) {
            PipeFilterEvent filterEvent = new PipeFilterEvent(bl, items, new HashSet<>(signFilters.filters), new HashSet<>(signFilters.exceptions), filteredItems);
            Bukkit.getPluginManager().callEvent(filterEvent);

            filteredItems = filterEvent.getFilteredItems();
        }

        if(filteredItems.isEmpty())
            return runPassThrough;

        List<ItemStack> newItems = new ArrayList<>();

        Block fac = bl.getRelative(p.getFacing());

        PipePutEvent event = new PipePutEvent(bl, new ArrayList<>(filteredItems), fac);
        Bukkit.getPluginManager().callEvent(event);

        if (!event.isCancelled()) {
            if (InventoryUtil.doesBlockHaveInventory(fac)) {
                InventoryHolder holder = (InventoryHolder) PaperLib.getBlockState(fac, false).getState();
                newItems.addAll(InventoryUtil.addItemsToInventory(holder, event.getItems().toArray(new ItemStack[event.getItems().size()])));
            } else if (fac.getType() == Material.JUKEBOX) {
                Jukebox juke = (Jukebox) fac.getState();
                List<ItemStack> its = new ArrayList<>(event.getItems());
                // Only an empty jukebox accepts a disc. The test used to be inverted,
                // which meant an empty jukebox never took one, and a playing one had its
                // disc overwritten by setPlaying and destroyed. Discs that do not fit
                // stay in the list and flow on through the pipe.
                if (juke.getPlaying() == Material.AIR) {
                    Iterator<ItemStack> iter = its.iterator();
                    while (iter.hasNext()) {
                        ItemStack st = iter.next();
                        if (!st.getType().isRecord()) continue;
                        juke.setPlaying(st.getType());
                        juke.update();
                        if (st.getAmount() > 1)
                            st.setAmount(st.getAmount() - 1);
                        else
                            iter.remove();
                        break;
                    }
                }
                newItems.addAll(its);
            } else {
                newItems.addAll(event.getItems());
            }

            items.removeAll(filteredItems);
            items.addAll(newItems);
        }
        return true;
    }

    /**
     * Same contract as {@link #processPistonOutput}, for droppers.
     */
    private boolean processDropperOutput(Block bl, List<ItemStack> items, CachedFilters signFilters, boolean runPassThrough) {
        List<ItemStack> filteredItems = new ArrayList<>(VerifyUtil.withoutNulls(ItemUtil.filterItemsLoose(items, signFilters.filters, signFilters.exceptions)));

        if(filteredItems.isEmpty())
            return runPassThrough;

        if (bl.getType() != Material.DROPPER)
            return true;
        Dropper dropper = (Dropper) PaperLib.getBlockState(bl, false).getState();
        List<ItemStack> newItems =
                new ArrayList<>(dropper.getInventory().addItem(filteredItems.toArray(new ItemStack[filteredItems.size()])).values());

        int dropsLeft = pipeDropperDropLimit > 0 ? pipeDropperDropLimit : Integer.MAX_VALUE;
        for(ItemStack stack : dropper.getInventory().getContents()) {
            if(!ItemUtil.isStackValid(stack))
                continue;
            for(int i = 0; i < stack.getAmount() && dropsLeft > 0; i++, dropsLeft--)
                dropper.drop();
            if (dropsLeft == 0)
                break;
        }

        items.removeAll(filteredItems);
        items.addAll(newItems);
        return true;
    }

    private static boolean isValidPipeBlock(Material type) {
        return switch (type) {
            case GLASS, PISTON, STICKY_PISTON, DROPPER, GLASS_PANE -> true;
            default -> ItemUtil.isStainedGlass(type)
                    || ItemUtil.isStainedGlassPane(type)
                    || SignUtil.isWallSign(type);
        };
    }

    /**
     * Fabrication guard, active only with the "pipes" debug flag.
     *
     * Every branch that moves an item out of a block and into the pipe's item list must
     * also clear it from that block. The jukebox branch once did neither: it built a new
     * ItemStack from the block state and left the original in place, so the same disc
     * existed both in the network and in the world. An item-count ledger cannot catch
     * that - the counts balance - so the check has to be "is it still in the source?".
     *
     * Call after the removal, with whatever the source reports for that slot afterwards.
     *
     * Reported once per source block per session, not once per pulse: this is a static
     * code fault, so a clocked piston would otherwise write the same line ten times a
     * second and bury the debug output it was turned on to read.
     */
    private static final LongOpenHashSet reportedFabrications = new LongOpenHashSet();

    private static void auditTaken(Block source, ItemStack taken, ItemStack stillInSource) {
        if (taken == null || !CraftBookPlugin.isDebugFlagEnabled("pipes"))
            return;
        if (stillInSource == null || !ItemUtil.areItemsIdentical(taken, stillInSource))
            return;
        if (reportedFabrications.size() > 256)
            reportedFabrications.clear();
        if (!reportedFabrications.add(posKey(source)))
            return;
        CraftBookPlugin.logger().warning("[Pipes] Fabrication: took " + taken.getType() + " x" + taken.getAmount()
                + " from " + source.getType() + " @ " + source.getWorld().getName() + " "
                + source.getX() + " " + source.getY() + " " + source.getZ()
                + " but the source still holds it - the item now exists twice."
                + " (reported once per block; further occurrences here are silent)");
    }

    private org.bukkit.scheduler.BukkitTask smokeTask;

    @Override
    public boolean enable() {
        smokeTask = Bukkit.getScheduler().runTaskTimer(CraftBookPlugin.inst(), this::smokeBlockedPistons, 12L, 12L);
        return true;
    }

    @Override
    public void disable() {
        if (smokeTask != null) {
            smokeTask.cancel();
            smokeTask = null;
        }
        typeCache.clear();
    }

    /**
     * Keeps every blocked piston visibly smoking between pulses. The flag is state, set
     * on a totally failed pull and cleared by the next successful one, so a player can
     * find a defective system by looking at it, not only by watching a pulse happen.
     */
    private void smokeBlockedPistons() {
        if (!pipeFullSmoke)
            return;
        for (Map.Entry<UUID, WorldTypes> entry : typeCache.entrySet()) {
            WorldTypes wt = entry.getValue();
            if (wt.blockedPistons.isEmpty())
                continue;
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null)
                continue;
            it.unimi.dsi.fastutil.longs.LongIterator iter = wt.blockedPistons.iterator();
            while (iter.hasNext()) {
                long key = iter.nextLong();
                int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
                if (!world.isChunkLoaded(x >> 4, z >> 4))
                    continue;
                Block piston = world.getBlockAt(x, y, z);
                if (piston.getType() != Material.STICKY_PISTON) {
                    iter.remove();
                    continue;
                }
                // No container in front means there is no system left to be blocked:
                // the flag would otherwise smoke forever after the source is removed,
                // since only a pull attempt clears it and none can ever happen again.
                Block fac = piston.getRelative(((Piston) piston.getBlockData()).getFacing());
                if (!InventoryUtil.doesBlockHaveInventory(fac)) {
                    iter.remove();
                    continue;
                }
                world.spawnParticle(org.bukkit.Particle.LARGE_SMOKE,
                        x + 0.5, y + 1.2, z + 0.5, 4, 0.15, 0.1, 0.15, 0.01);
            }
        }
    }

    private static int unpackX(long key) { return (int) (key >> 38); }
    private static int unpackZ(long key) { return (int) (key << 26 >> 38); }
    private static int unpackY(long key) { return (int) (key << 52 >> 52); }

    /**
     * A visible signal on a paused piston: black smoke rising while pulses arrive and
     * the network is refusing items. With links and cross-dimension receivers the
     * blockage is otherwise invisible from the sending side. Throttled per piston so a
     * fast clock reads as a steady chimney rather than a particle storm.
     */
    private void spawnPauseSmoke(Block piston) {
        if (!pipeFullSmoke)
            return;
        WorldTypes wt = caches(piston.getWorld());
        long now = System.currentTimeMillis();
        long key = posKey(piston);
        if (now - wt.smokeAt.get(key) < 600L)
            return;
        wt.smokeAt.put(key, now);
        piston.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE,
                piston.getX() + 0.5, piston.getY() + 1.2, piston.getZ() + 0.5,
                5, 0.15, 0.1, 0.15, 0.01);
    }

    private void startPipe(Block block, List<ItemStack> items, boolean request) {
        startPipe(block, items, request, null);
    }

    private void startPipe(Block block, List<ItemStack> items, boolean request, Block hopReturn) {

        CachedFilters signFilters = getFilters(block);
        HashSet<ItemStack> filters = signFilters.filters;
        HashSet<ItemStack> exceptions = signFilters.exceptions;

        // 'pass'/'bypass'/'b' on the starting sign makes this whole run pass-through:
        // unmatched items flow past filtered pistons instead of stopping there.
        boolean runPassThrough = pipePassThrough || signFilters.passThrough;

        // Primitive set: the BFS adds one key per visited block per pulse.
        LongOpenHashSet visitedPipes = new LongOpenHashSet();

        if (block.getType() == Material.STICKY_PISTON) {

            List<ItemStack> leftovers = new ArrayList<>();

            Piston p = (Piston) block.getBlockData();
            Block fac = block.getRelative(p.getFacing());
            Material facType = fac.getType();

            // Never pull out of containers inside someone else's claim.
            if (!com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous.PipeLinkProtection.mayPipePull(block, fac))
                return;

            // A piston whose whole network refused the last pull is paused briefly, so a
            // full pipe fed by e.g. a self-triggered collector stops re-traversing and
            // vomiting items on every pulse.
            if (pipeFullCooldownMillis > 0) {
                Long2LongOpenHashMap backoff = caches(block.getWorld()).fullBackoff;
                long pistonKey = posKey(block);
                long pausedUntil = backoff.get(pistonKey);
                if (pausedUntil != 0L) {
                    if (System.currentTimeMillis() < pausedUntil) {
                        spawnPauseSmoke(block);
                        // Requests carrying items (e.g. a ranged collector feeding this
                        // piston) still buffer into the source container while paused.
                        if (!items.isEmpty() && InventoryUtil.doesBlockHaveInventory(fac)) {
                            InventoryHolder pausedHolder = (InventoryHolder) PaperLib.getBlockState(fac, false).getState();
                            List<ItemStack> rest = InventoryUtil.addItemsToInventory(pausedHolder, items.toArray(new ItemStack[0]));
                            items.clear();
                            items.addAll(rest);
                        }
                        return;
                    }
                    backoff.remove(pistonKey);
                }
            }

            if (facType == Material.CHEST
                    || facType == Material.TRAPPED_CHEST
                    || facType == Material.DROPPER
                    || facType == Material.DISPENSER
                    || facType == Material.HOPPER
                    || facType == Material.BARREL
                    || facType == Material.CHISELED_BOOKSHELF
                    || facType == Material.CRAFTER
                    || facType == Material.DECORATED_POT
                    || Tag.SHULKER_BOXES.isTagged(facType)) {
                InventoryHolder sourceHolder = (InventoryHolder) PaperLib.getBlockState(fac, false).getState();
                // Per-slot reads instead of getContents(): that call copies the whole
                // inventory array every pulse just to pull one stack.
                int slots = sourceHolder.getInventory().getSize();

                // Scan starting after the slot pulled last pulse, so a stack no output
                // accepts (returned as leftovers) cannot block everything behind it.
                int startSlot = 0;
                Long2IntOpenHashMap cursor = null;
                if (pipeRoundRobinPull && pipeStackPerPull && slots > 0) {
                    cursor = caches(block.getWorld()).pullCursor;
                    startSlot = cursor.get(posKey(block)) % slots;
                }

                for (int off = 0; off < slots; off++) {
                    int slot = (startSlot + off) % slots;
                    ItemStack stack = sourceHolder.getInventory().getItem(slot);

                    if (!ItemUtil.isStackValid(stack))
                        continue;

                    if(!ItemUtil.doesItemPassLooseFilters(stack, filters, exceptions))
                        continue;

                    items.add(stack);
                    sourceHolder.getInventory().setItem(slot, null);
                    auditTaken(fac, stack, sourceHolder.getInventory().getItem(slot));
                    if (pipeStackPerPull) {
                        if (cursor != null)
                            cursor.put(posKey(block), slot + 1);
                        break;
                    }
                }

                int pulledAmount = 0;
                for (ItemStack pulled : items)
                    if (pulled != null)
                        pulledAmount += pulled.getAmount();

                PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                Bukkit.getPluginManager().callEvent(event);
                items.clear();
                items.addAll(event.getItems());
                if(!event.isCancelled()) {
                    visitedPipes.add(posKey(fac));
                    searchNearbyPipes(block, visitedPipes, items, runPassThrough);
                }

                if (!items.isEmpty()) {
                    int undelivered = 0;
                    for (ItemStack left : items)
                        if (left != null)
                            undelivered += left.getAmount();
                    if (pipeFullCooldownMillis > 0 && pulledAmount > 0 && undelivered >= pulledAmount) {
                        WorldTypes wt = caches(block.getWorld());
                        long pistonKey = posKey(block);
                        wt.fullBackoff.put(pistonKey, System.currentTimeMillis() + pipeFullCooldownMillis);
                        // Blocked is a STATE, not an event: the flag keeps the piston
                        // smoking between pulses via the repeating task, until a pull
                        // succeeds again. A puff only at pulse time is missable.
                        wt.blockedPistons.add(pistonKey);
                        spawnPauseSmoke(block);
                    } else {
                        caches(block.getWorld()).blockedPistons.remove(posKey(block));
                    }

                    if (facType == Material.CRAFTER)
                        leftovers.addAll(InventoryUtil.addItemsToCrafter((Crafter) PaperLib.getBlockState(fac, false).getState(), items.toArray(new ItemStack[items.size()])));
                    else {
                        for (ItemStack item : items) {
                            if (item == null) continue;
                            leftovers.addAll(sourceHolder.getInventory().addItem(item).values());
                        }
                    }
                } else {
                    // Everything delivered (or nothing to pull): the piston is not blocked.
                    caches(block.getWorld()).blockedPistons.remove(posKey(block));
                }
            } else if (facType == Material.FURNACE || facType == Material.BLAST_FURNACE || facType == Material.SMOKER) {

                Furnace f = (Furnace) PaperLib.getBlockState(fac, false).getState();

                if (!ItemUtil.isStackValid(f.getInventory().getResult()))
                    return;

                if(!ItemUtil.doesItemPassLooseFilters(f.getInventory().getResult(), filters, exceptions))
                    return;
                ItemStack takenResult = f.getInventory().getResult();
                items.add(takenResult);
                if (f.getInventory().getResult() != null) f.getInventory().setResult(null);
                auditTaken(fac, takenResult, f.getInventory().getResult());

                PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                Bukkit.getPluginManager().callEvent(event);
                items.clear();
                items.addAll(event.getItems());
                if(!event.isCancelled()) {
                    visitedPipes.add(posKey(fac));
                    searchNearbyPipes(block, visitedPipes, items, runPassThrough);
                }

                if (!items.isEmpty()) {
                    for (ItemStack item : items) {
                        if (item == null) continue;
                        if(f.getInventory().getResult() == null)
                            f.getInventory().setResult(item);
                        else
                            leftovers.add(ItemUtil.addToStack(f.getInventory().getResult(), item));
                    }
                } else f.getInventory().setResult(null);
            } else if (facType == Material.JUKEBOX) {

                Jukebox juke = (Jukebox) fac.getState();

                if (juke.getPlaying() != Material.AIR) {
                    // Take the disc out BEFORE the event fires. "The disc left the
                    // jukebox" must be a fact, not inferred from the item list after
                    // delivery - the old inference duplicated the disc whenever any
                    // leftover kept the list non-empty, and an empty jukebox used to
                    // swallow incoming payloads because this branch never fed
                    // leftovers before the shared items.clear() below.
                    ItemStack takenDisc = new ItemStack(juke.getPlaying());
                    items.add(takenDisc);
                    juke.setPlaying(Material.AIR);
                    juke.update();
                    Material stillPlaying = ((Jukebox) fac.getState()).getPlaying();
                    auditTaken(fac, takenDisc, stillPlaying == Material.AIR ? null : new ItemStack(stillPlaying));
                }

                if (!items.isEmpty()) {
                    PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                    Bukkit.getPluginManager().callEvent(event);
                    items.clear();
                    items.addAll(event.getItems());

                    if (!event.isCancelled() && !items.isEmpty()) {
                        visitedPipes.add(posKey(fac));
                        searchNearbyPipes(block, visitedPipes, items, runPassThrough);
                    }
                }
                leftovers.addAll(items);
            } else {
                PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                Bukkit.getPluginManager().callEvent(event);
                items.clear();
                items.addAll(event.getItems());
                if(!event.isCancelled() && !items.isEmpty()) {
                    visitedPipes.add(posKey(fac));
                    searchNearbyPipes(block, visitedPipes, items, runPassThrough);
                }
                leftovers.addAll(items);
            }

            if (PipeFinishEvent.getHandlerList().getRegisteredListeners().length > 0) {
                PipeFinishEvent fEvent = new PipeFinishEvent(block, leftovers, fac, request);
                Bukkit.getPluginManager().callEvent(fEvent);
                leftovers = fEvent.getItems();
            }
            items.clear();

            if (!leftovers.isEmpty()) {
                for (ItemStack item : leftovers) {
                    if (!ItemUtil.isStackValid(item)) continue;
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item);
                }
            }
        } else if (request && isValidPipeBlock(block.getType())) {
            // PipeLink hop: items arriving from a linked sender are injected into the pipe
            // network at this block, which need not be a sticky piston.
            PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), block);
            Bukkit.getPluginManager().callEvent(event);
            items.clear();
            items.addAll(event.getItems());
            if (!event.isCancelled() && !items.isEmpty()) {
                visitedPipes.add(posKey(block));
                searchNearbyPipes(block, visitedPipes, items, runPassThrough);
            }

            List<ItemStack> leftovers = new ArrayList<>(items);
            if (PipeFinishEvent.getHandlerList().getRegisteredListeners().length > 0) {
                PipeFinishEvent fEvent = new PipeFinishEvent(block, leftovers, block, true);
                Bukkit.getPluginManager().callEvent(fEvent);
                leftovers = fEvent.getItems();
            }
            items.clear();

            if (!leftovers.isEmpty()) {
                if (hopReturn != null && InventoryUtil.doesBlockHaveInventory(hopReturn)) {
                    InventoryHolder holder = (InventoryHolder) PaperLib.getBlockState(hopReturn, false).getState();
                    leftovers = InventoryUtil.addItemsToInventory(holder, leftovers.toArray(new ItemStack[0]));
                }
                Block dropAt = hopReturn != null ? hopReturn : block;
                for (ItemStack item : leftovers) {
                    if (!ItemUtil.isStackValid(item)) continue;
                    dropAt.getWorld().dropItemNaturally(dropAt.getLocation().add(0.5, 0.5, 0.5), item);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockRedstoneChange(SourcedBlockRedstoneEvent event){

        if (event.getBlock().getType() == Material.STICKY_PISTON) {

            if (pipeRequireSign && !getFilters(event.getBlock()).hasSign)
                return;

            if(!EventUtil.passesFilter(event)) return;

            startPipe(event.getBlock(), new ArrayList<>(), false);
        }
    }

    // ignoreCancelled: the link router cancels requests it has delivered; running anyway
    // would pull a fresh, unrequested stack out of the piston's source container.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPipeRequest(PipeRequestEvent event) {

        boolean stickyStart = event.getBlock().getType() == Material.STICKY_PISTON;
        boolean hopStart = event.isFromHop() && isValidPipeBlock(event.getBlock().getType());

        if (!stickyStart && !hopStart)
            return;

        if (pipeRequireSign && stickyStart && !getFilters(event.getBlock()).hasSign)
            return;

        if(!EventUtil.passesFilter(event)) return;

        Block hopReturn = event.isFromHop() ? event.getSuckedBlock() : null;
        startPipe(event.getBlock(), event.getItems(), true, hopReturn);
    }

    private boolean pipesDiagonal;
    private BlockStateHolder<?> pipeInsulator;
    private boolean pipeStackPerPull;
    private boolean pipeRequireSign;
    private boolean pipeFilterCache;
    private boolean pipeRoundRobinPull;
    private int pipeFullCooldownMillis;
    private boolean pipeFullSmoke;
    private int pipeDropperDropLimit;
    private boolean pipePassThrough;
    private boolean pipeTraversalCache;

    @Override
    public void loadConfiguration (YAMLProcessor config, String path) {

        config.setComment(path + "allow-diagonal", "Allow pipes to work diagonally. Required for insulators to work.");
        pipesDiagonal = config.getBoolean(path + "allow-diagonal", false);

        config.setComment(path + "insulator-block", "When pipes work diagonally, this block allows the pipe to be insulated to not work diagonally.");
        pipeInsulator = BlockSyntax.getBlock(config.getString(path + "insulator-block", BlockTypes.WHITE_WOOL.id()), true);

        config.setComment(path + "stack-per-move", "This option stops the pipes taking the entire chest on power, and makes it just take a single stack.");
        pipeStackPerPull = config.getBoolean(path + "stack-per-move", true);

        config.setComment(path + "require-sign", "Requires pipes to have a [Pipe] sign connected to them. This is the only way to require permissions to make pipes.");
        pipeRequireSign = config.getBoolean(path + "require-sign", false);

        config.setComment(path + "filter-cache", "Cache parsed [Pipe] filter signs for performance. Purely an optimisation; disable if filters ever seem outdated.");
        pipeFilterCache = config.getBoolean(path + "filter-cache", true);

        config.setComment(path + "dropper-drop-limit", "Maximum items a pipe dropper ejects per activation. 0 keeps the vanilla behaviour of dropping its entire inventory.");
        pipeDropperDropLimit = config.getInt(path + "dropper-drop-limit", 0);

        config.setComment(path + "traversal-cache", "Cache pipe block-type lookups between pulses. Auto-invalidated on block changes, and fully expires every 5 seconds as a safety net for eventless changes (WorldEdit, plugin API). Deposits are always verified live.");
        pipeTraversalCache = config.getBoolean(path + "traversal-cache", true);
        typeCache.clear();

        config.setComment(path + "filters-match-type", "When a pipe or collector filter entry has no item meta, match by item type alone so potions, enchanted books and renamed items are caught by plain filters instead of passing through. Disable for strict vanilla meta matching.");
        com.sk89q.craftbook.util.ItemUtil.setLooseFilterMatching(config.getBoolean(path + "filters-match-type", true));

        config.setComment(path + "full-pipe-cooldown", "Seconds a sticky piston waits before pulling again after a pulse where nothing could be delivered anywhere (full network). Stops full pipes wasting full traversals and vomiting items every pulse. 0 disables.");
        pipeFullCooldownMillis = config.getInt(path + "full-pipe-cooldown", 2) * 1000;

        config.setComment(path + "full-pipe-smoke", "Show black smoke above a paused piston while pulses arrive and its network is refusing items. Makes a blocked far end visible from the sending side, which matters for links and cross-dimension receivers.");
        pipeFullSmoke = config.getBoolean(path + "full-pipe-smoke", true);

        config.setComment(path + "round-robin-pull", "Pull container slots in rotation instead of always taking the first stack, so one stack no output accepts cannot block everything behind it. Disable for strict vanilla first-stack behaviour.");
        pipeRoundRobinPull = config.getBoolean(path + "round-robin-pull", true);

        config.setComment(path + "claim-protect-pulls", "Stop pipes pulling items out of containers inside a GriefPrevention claim unless the pulling piston stands in a claim with the same owner. Closes a theft vector vanilla hoppers don't have. Ignored when GriefPrevention is not installed.");
        com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous.PipeLinkProtection.setProtectPulls(config.getBoolean(path + "claim-protect-pulls", true));

        config.setComment(path + "link-world-blacklist", "Worlds where PipeLink may not bind or deliver at all (either endpoint). Useful to keep e.g. a creative world on the same server out of survival item flows. Empty by default.");
        com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous.PipeLinkProtection.setWorldBlacklist(config.getStringList(path + "link-world-blacklist", new java.util.ArrayList<>()));

        config.setComment(path + "link-isolated-worlds", "Worlds that may only PipeLink within themselves: links inside the world work normally, but links crossing into or out of it are refused (existing ones lie dormant). Empty by default.");
        com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous.PipeLinkProtection.setIsolatedWorlds(config.getStringList(path + "link-isolated-worlds", new java.util.ArrayList<>()));

        config.setComment(path + "link-cross-dimension", "Allow PipeLink senders and receivers to be linked across dimensions (overworld/nether/end). When disabled, new cross-dimension bindings are refused and existing ones lie dormant until re-enabled.");
        com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous.PipeLinkProtection.setAllowCrossDimension(config.getBoolean(path + "link-cross-dimension", true));

        config.setComment(path + "link-protection", "GriefPrevention trust required to bind PipeLink senders and receivers inside a claim: none, access, container, build or permission. Claim owners can always bind; unclaimed land is always allowed. Ignored when GriefPrevention is not installed.");
        com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous.PipeLinkProtection.setLevel(config.getString(path + "link-protection", "permission"));

        config.setComment(path + "pass-through", "Make every pipe run pass-through: items that match no filter continue past filtered pistons and droppers instead of stopping there. The same can be enabled per run by writing 'pass', 'bypass' or 'b' on the first line of the starting sticky piston's [Pipe] sign. On an output piston or dropper's own sign, the marker instead makes that block a plain conduit items always flow through untouched.");
        pipePassThrough = config.getBoolean(path + "pass-through", false);
    }
}
