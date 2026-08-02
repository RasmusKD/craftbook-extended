package com.sk89q.craftbook.mechanics.ic.gates.world.items;

import com.google.common.collect.Lists;
import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import com.sk89q.craftbook.mechanics.ic.AbstractICFactory;
import com.sk89q.craftbook.mechanics.ic.AbstractSelfTriggeredIC;
import com.sk89q.craftbook.mechanics.ic.ChipState;
import com.sk89q.craftbook.mechanics.ic.IC;
import com.sk89q.craftbook.mechanics.ic.ICFactory;
import com.sk89q.craftbook.mechanics.ranged.RangedCollectEvent;
import com.sk89q.craftbook.util.ICUtil;
import com.sk89q.craftbook.util.InventoryUtil;
import com.sk89q.craftbook.util.ItemSyntax;
import com.sk89q.craftbook.util.ItemUtil;
import com.sk89q.craftbook.util.LocationUtil;
import com.sk89q.craftbook.util.RegexUtil;
import com.sk89q.craftbook.util.SignUtil;
import com.sk89q.worldedit.math.Vector3;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RangedCollector extends AbstractSelfTriggeredIC {

    public RangedCollector (Server server, ChangedSign sign, ICFactory factory) {
        super(server, sign, factory);
    }

    @Override
    public String getTitle() {

        return "Ranged Collector";
    }

    @Override
    public String getSignTitle() {

        return "RANGED COLLECTOR";
    }

    @Override
    public void think (ChipState chip) {

        if(chip.getInput(0)) return;

        chip.setOutput(0, collect());
    }

    @Override
    public void trigger (ChipState chip) {
        if (chip.getInput(0))
            chip.setOutput(0, collect());
    }

    private Vector3 radius;
    private Location centre;

    private boolean include = false;

    private List<ItemStack> filters = new ArrayList<>();

    @Override
    public void load() {

        radius = ICUtil.parseRadius(getSign());
        String radiusString = radius.x() + "," + radius.y() + "," + radius.z();
        if(radius.x() == radius.y() && radius.y() == radius.z())
            radiusString = String.valueOf(radius.x());
        if (getLine(2).contains("=")) {
            getSign().setLine(2, radiusString + "=" + RegexUtil.EQUALS_PATTERN.split(getLine(2))[1]);
            centre = ICUtil.parseBlockLocation(getSign(), 2).getLocation();
        } else {
            getSign().setLine(2, radiusString);
            centre = getBackBlock().getLocation();
        }

        include = !getLine(3).startsWith("-");

        // load() can run more than once for the same IC; without this the filter list
        // accumulates duplicates and the per-item filter loop grows unboundedly.
        filters.clear();
        for(String bit : getLine(3).replace("-","").split(",")) {
            if (bit.trim().length() > 0) {
                ItemStack item = ItemSyntax.getItem(bit);
                if (ItemUtil.isStackValid(item)) {
                    filters.add(ItemSyntax.getItem(bit));
                }
            }
        }

    }

    /**
     * The container collected items go into: the block above the sign's backing block
     * (the classic placement), falling back to the backing block itself when the sign
     * sits directly on a container and nothing stands above it. Resolved per collection
     * so containers placed after the sign are picked up too.
     */
    private Block resolveContainer() {
        Block back = getBackBlock();
        Block above = back.getRelative(0, 1, 0);
        if (InventoryUtil.doesBlockHaveInventory(above))
            return above;
        if (InventoryUtil.doesBlockHaveInventory(back))
            return back;
        return above;
    }

    public boolean collect() {

        boolean collected = false;

        List<Item> itemsForChest = Lists.newArrayList();

        // Resolved lazily on the first entity that actually passes the filters: the sign
        // lookup is a block state read, and this method runs every self-trigger tick on
        // every magnet, almost always with nothing in radius to collect.
        boolean pipeResolved = false;
        Block pipe = null;

        // Precise spatial lookup instead of scanning full entity arrays of 9 chunks.
        // The predicate keeps the original radius semantics (spherical when symmetric).
        for (Item entity : centre.getWorld().<Item>getNearbyEntitiesByType(Item.class, centre, radius.x(), radius.y(), radius.z(),
                e -> e.isValid() && e.getPickupDelay() < 1 && LocationUtil.isWithinRadius(centre, e.getLocation(), radius))) {
            ItemStack stack = entity.getItemStack();

            // Skip only this entity - a return here would abort the whole radius pass
            // and starve the collector while a single invalid item entity lingers.
            if(!ItemUtil.isStackValid(stack))
                continue;

            boolean passed = filters.isEmpty() || !include;

            for(ItemStack filter : filters) {
                if(!ItemUtil.isStackValid(filter))
                    continue;

                if(include && ItemUtil.matchesFilter(filter, stack)) {
                    passed = true;
                    break;
                } else if(!include && ItemUtil.matchesFilter(filter, stack)) {
                    passed = false;
                    break;
                }
            }

            if (!passed) {
                continue;
            }

            if (!pipeResolved) {
                pipeResolved = true;
                if (RangedCollectEvent.getHandlerList().getRegisteredListeners().length > 0) {
                    org.bukkit.block.Sign signState = CraftBookBukkitUtil.toSign(getSign());
                    if (signState != null)
                        pipe = getBackBlock().getRelative(SignUtil.getBack(signState.getBlock()));
                }
            }

            if (pipe != null) {
                RangedCollectEvent event = new RangedCollectEvent(pipe, entity, new ArrayList<>(Collections.singletonList(stack)), getBackBlock());
                Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    continue;
                }

                if(event.getItems().isEmpty()) {
                    entity.remove();
                    return true;
                }

                // Pipes may have consumed part of the stack already (the paused
                // full-pipe branch inserts what fits into the source container), so
                // trim the entity to what is still outstanding - otherwise the chest
                // path below re-delivers the already-inserted portion.
                int outstanding = 0;
                for (ItemStack left : event.getItems())
                    if (left != null)
                        outstanding += left.getAmount();
                if (outstanding <= 0) {
                    // Non-empty list summing to zero is still "everything consumed".
                    entity.remove();
                    return true;
                }
                if (outstanding < stack.getAmount()) {
                    ItemStack trimmed = stack.clone();
                    trimmed.setAmount(outstanding);
                    entity.setItemStack(trimmed);
                }
            }

            itemsForChest.add(entity);
        }

        if (!itemsForChest.isEmpty()) {
            Block container = resolveContainer();
            if(!InventoryUtil.doesBlockHaveInventory(container))
                return false;

            InventoryHolder chestState = (InventoryHolder) PaperLib.getBlockState(container, false).getState();

            // Add the items to a container, and destroy them.
            for (Item entity : itemsForChest) {
                ItemStack stack = entity.getItemStack();
                int before = stack.getAmount();
                // Clone before handing over: addItem mutates its argument in place and
                // returns that same object as the leftover, so identity comparisons are
                // meaningless and the entity's own stack must only ever change through
                // an explicit setItemStack below.
                List<ItemStack> leftovers = InventoryUtil.addItemsToInventory(chestState, false, stack.clone());
                int remaining = 0;
                for (ItemStack l : leftovers)
                    if (l != null)
                        remaining += l.getAmount();
                if (remaining >= before)
                    continue; // full container, nothing moved - do not signal a collection
                collected = true;
                if (remaining <= 0) {
                    entity.remove();
                } else {
                    ItemStack trimmed = stack.clone();
                    trimmed.setAmount(remaining);
                    entity.setItemStack(trimmed);
                }
            }

            //if (collected) {
            //    chestState.update();
            //}
        }

        return collected;
    }

    public static class Factory extends AbstractICFactory {

        public Factory(Server server) {

            super(server);
        }

        @Override
        public IC create(ChangedSign sign) {

            return new RangedCollector(getServer(), sign, this);
        }

        @Override
        public String getShortDescription() {

            return "Collects items at a range into above chest.";
        }

        @Override
        public String[] getLineHelp() {

            return new String[] {"radius=x:y:z offset", "{-}id:data{,id:data}"};
        }
    }
}