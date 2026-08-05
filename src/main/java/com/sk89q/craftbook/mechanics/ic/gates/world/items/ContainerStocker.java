package com.sk89q.craftbook.mechanics.ic.gates.world.items;

import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import com.sk89q.craftbook.mechanics.ic.AbstractICFactory;
import com.sk89q.craftbook.mechanics.ic.AbstractSelfTriggeredIC;
import com.sk89q.craftbook.mechanics.ic.ChipState;
import com.sk89q.craftbook.mechanics.ic.IC;
import com.sk89q.craftbook.mechanics.ic.ICFactory;
import com.sk89q.craftbook.mechanics.ic.RestrictedIC;
import com.sk89q.craftbook.mechanics.pipe.PipeRequestEvent;
import com.sk89q.craftbook.util.ICUtil;
import com.sk89q.craftbook.util.InventoryUtil;
import com.sk89q.craftbook.util.ItemSyntax;
import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;

public class ContainerStocker extends AbstractSelfTriggeredIC {

    public ContainerStocker(Server server, ChangedSign sign, ICFactory factory) {

        super(server, sign, factory);
    }

    ItemStack item;
    Location offset;

    @Override
    public void load() {

        if(getLine(3).isEmpty())
            offset = getBackBlock().getRelative(0, 1, 0).getLocation();
        else
            offset = ICUtil.parseBlockLocation(getSign(), 3).getLocation();
        item = ItemSyntax.getItem(getLine(2));
    }

    @Override
    public String getTitle() {

        return "Container Stocker";
    }

    @Override
    public String getSignTitle() {

        return "STOCKER";
    }

    @Override
    public void trigger(ChipState chip) {

        if (chip.getInput(0)) chip.setOutput(0, stock());
    }

    @Override
    public void think(ChipState chip) {

        chip.setOutput(0, stock());
    }

    public boolean stock() {

        if (!InventoryUtil.doesBlockHaveInventory(offset.getBlock()))
            return false;

        InventoryHolder target = (InventoryHolder) offset.getBlock().getState();
        // Only ask for what is known to fit: the extraction removes real items from the
        // source container, so nothing may be pulled that could end up homeless.
        if (!hasRoomFor(target.getInventory(), item))
            return false;

        BlockFace back = SignUtil.getBack(CraftBookBukkitUtil.toSign(getSign()).getBlock());
        Block pipe = getBackBlock().getRelative(back);

        // Extraction wish list: pipes replace it with matching items actually removed
        // from the source container. The old plain request injected the wish into a
        // normal pull cycle, which fabricated one item per trigger into the source.
        PipeRequestEvent event = new PipeRequestEvent(pipe, new ArrayList<>(Collections.singletonList(item.clone())), getBackBlock());
        event.setExtract(true);
        Bukkit.getPluginManager().callEvent(event);

        if(!event.isValid())
            return false;

        boolean stocked = false;
        for (ItemStack stack : event.getItems()) {
            if (stack == null)
                continue;
            java.util.Map<Integer, ItemStack> rest = target.getInventory().addItem(stack);
            if (rest.isEmpty()) {
                stocked = true;
            } else if (pipe.getBlockData() instanceof org.bukkit.block.data.type.Piston piston) {
                // The room check above makes this near-unreachable; if it ever happens,
                // hand the overflow back to the source container rather than losing it.
                Block source = pipe.getRelative(piston.getFacing());
                if (InventoryUtil.doesBlockHaveInventory(source)) {
                    InventoryUtil.addItemsToInventory((InventoryHolder) source.getState(),
                            rest.values().toArray(new ItemStack[0]));
                }
            }
        }
        return stocked;
    }

    private static boolean hasRoomFor(org.bukkit.inventory.Inventory inv, ItemStack want) {
        int room = 0;
        for (ItemStack have : inv.getStorageContents()) {
            if (have == null || have.getType() == org.bukkit.Material.AIR)
                return true;
            if (have.isSimilar(want))
                room += have.getMaxStackSize() - have.getAmount();
            if (room >= want.getAmount())
                return true;
        }
        return false;
    }

    public static class Factory extends AbstractICFactory implements RestrictedIC {

        public Factory(Server server) {

            super(server);
        }

        @Override
        public IC create(ChangedSign sign) {

            return new ContainerStocker(getServer(), sign, this);
        }

        @Override
        public String getShortDescription() {

            return "Adds item into container at specified offset.";
        }

        @Override
        public String[] getLineHelp() {

            return new String[] {"item id:data", "x:y:z offset"};
        }
    }
}