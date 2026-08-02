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
        invalidateFilterCacheAround(event.getBlock().getLocation());

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

    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    private final Map<Location, CachedFilters> filterCache = new ConcurrentHashMap<>();

    /** Last pulled slot per source piston, for round-robin pulling. */
    private final Map<Location, Integer> pullCursor = new ConcurrentHashMap<>();

    /** Pistons paused after a pull where nothing could be delivered (full network). */
    private final Map<Location, Long> fullBackoff = new ConcurrentHashMap<>();

    private CachedFilters getFilters(Block block) {
        if (!pipeFilterCache)
            return parseFilters(block);

        Location loc = block.getLocation();
        CachedFilters cached = filterCache.get(loc);
        if (cached == null || cached.isStale()) {
            cached = parseFilters(block);
            filterCache.put(loc, cached);
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

    private void invalidateFilterCacheAround(Location center) {
        if (!pipeFilterCache || filterCache.isEmpty())
            return;
        // Signs attach to their block, so anything parsed from a sign within one block
        // of the change may now be outdated.
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    filterCache.remove(center.clone().add(x, y, z));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        invalidateFilterCacheAround(event.getBlock().getLocation());
        invalidateTypeCacheAt(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        invalidateFilterCacheAround(event.getBlock().getLocation());
        invalidateTypeCacheAt(event.getBlock());
    }

    // Rare bulk block changes just drop the whole per-world type cache.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        clearTypeCache(event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        clearTypeCache(event.getEntity().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        clearTypeCache(event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        clearTypeCache(event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        clearTypeCache(event.getBlock().getWorld());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        filterCache.keySet().removeIf(loc -> world.equals(loc.getWorld()));
        pullCursor.keySet().removeIf(loc -> world.equals(loc.getWorld()));
        fullBackoff.keySet().removeIf(loc -> world.equals(loc.getWorld()));
        typeCache.remove(world.getUID());
    }

    /* Traversal ------------------------------------------------------------------------ */

    private static long posKey(Block block) {
        return ((long) block.getX() & 0x3FFFFFFL) << 38
                | ((long) block.getZ() & 0x3FFFFFFL) << 12
                | (long) block.getY() & 0xFFFL;
    }

    /* Block type memoisation ------------------------------------------------------------
     *
     * The traversal re-reads the same block types on every pulse. They are memoised here
     * so repeat pulses run on hash lookups. Player block changes invalidate precisely via
     * events; bulk changes (explosions, pistons) drop the world's cache; and the whole
     * cache expires every few seconds as a safety net for eventless changes (WorldEdit,
     * plugin API). Anything actually deposited into is still verified live. */

    private static final long TYPE_CACHE_TTL_MILLIS = 5000L;

    private static final class WorldTypes {
        final Map<Long, Material> types = new ConcurrentHashMap<>();
        final Map<Long, Boolean> insulators = new ConcurrentHashMap<>();
        volatile long clearedAt = System.currentTimeMillis();
    }

    private final Map<UUID, WorldTypes> typeCache = new ConcurrentHashMap<>();

    private WorldTypes worldTypes(World world) {
        WorldTypes wt = typeCache.computeIfAbsent(world.getUID(), w -> new WorldTypes());
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
        Map<Long, Material> types = worldTypes(block.getWorld()).types;
        Long key = posKey(block);
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
        Map<Long, Boolean> insulators = worldTypes(block.getWorld()).insulators;
        Long key = posKey(block);
        Boolean cached = insulators.get(key);
        if (cached != null)
            return cached;
        boolean result = pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(block.getBlockData()));
        insulators.put(key, result);
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

    private void searchNearbyPipes(Block block, Set<Long> visitedPipes, List<ItemStack> items, boolean runPassThrough) {
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

    private void expandNeighbour(Block bl, Material blType, int x, int y, int z, Set<Long> visitedPipes, Deque<Block> searchQueue) {
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
                if (juke.getPlaying() != Material.AIR) {
                    Iterator<ItemStack> iter = its.iterator();
                    while (iter.hasNext()) {
                        ItemStack st = iter.next();
                        if (!st.getType().isRecord()) continue;
                        juke.setPlaying(st.getType());
                        juke.update();
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

        Set<Long> visitedPipes = new HashSet<>();

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
                Long pausedUntil = fullBackoff.get(block.getLocation());
                if (pausedUntil != null) {
                    if (System.currentTimeMillis() < pausedUntil)
                        return;
                    fullBackoff.remove(block.getLocation());
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
                ItemStack[] contents = sourceHolder.getInventory().getContents();
                int slots = contents.length;

                // Scan starting after the slot pulled last pulse, so a stack no output
                // accepts (returned as leftovers) cannot block everything behind it.
                int startSlot = 0;
                Location pullKey = null;
                if (pipeRoundRobinPull && pipeStackPerPull && slots > 0) {
                    pullKey = block.getLocation();
                    startSlot = pullCursor.getOrDefault(pullKey, 0) % slots;
                }

                for (int off = 0; off < slots; off++) {
                    int slot = (startSlot + off) % slots;
                    ItemStack stack = contents[slot];

                    if (!ItemUtil.isStackValid(stack))
                        continue;

                    if(!ItemUtil.doesItemPassLooseFilters(stack, filters, exceptions))
                        continue;

                    items.add(stack);
                    sourceHolder.getInventory().setItem(slot, null);
                    if (pipeStackPerPull) {
                        if (pullKey != null)
                            pullCursor.put(pullKey, slot + 1);
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
                    if (pipeFullCooldownMillis > 0 && pulledAmount > 0 && undelivered >= pulledAmount)
                        fullBackoff.put(block.getLocation(), System.currentTimeMillis() + pipeFullCooldownMillis);

                    if (facType == Material.CRAFTER)
                        leftovers.addAll(InventoryUtil.addItemsToCrafter((Crafter) fac.getState(), items.toArray(new ItemStack[items.size()])));
                    else {
                        for (ItemStack item : items) {
                            if (item == null) continue;
                            leftovers.addAll(sourceHolder.getInventory().addItem(item).values());
                        }
                    }
                }
            } else if (facType == Material.FURNACE || facType == Material.BLAST_FURNACE || facType == Material.SMOKER) {

                Furnace f = (Furnace) PaperLib.getBlockState(fac, false).getState();

                if (!ItemUtil.isStackValid(f.getInventory().getResult()))
                    return;

                if(!ItemUtil.doesItemPassLooseFilters(f.getInventory().getResult(), filters, exceptions))
                    return;
                items.add(f.getInventory().getResult());
                if (f.getInventory().getResult() != null) f.getInventory().setResult(null);

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
                    items.add(new ItemStack(juke.getPlaying()));

                    PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                    Bukkit.getPluginManager().callEvent(event);
                    items.clear();
                    items.addAll(event.getItems());

                    if (!event.isCancelled()) {
                        visitedPipes.add(posKey(fac));
                        searchNearbyPipes(block, visitedPipes, items, runPassThrough);
                    }

                    if (!items.isEmpty()) {
                        for (ItemStack item : items) {
                            if (!ItemUtil.isStackValid(item)) continue;
                            block.getWorld().dropItem(BlockUtil.getBlockCentre(block), item);
                        }
                    } else {
                        juke.setPlaying(Material.AIR);
                        juke.update();
                    }
                }
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
                    InventoryHolder holder = (InventoryHolder) hopReturn.getState();
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

    @EventHandler(priority = EventPriority.HIGH)
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
