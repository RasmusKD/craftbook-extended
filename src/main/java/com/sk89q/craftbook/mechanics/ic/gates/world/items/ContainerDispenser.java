package com.sk89q.craftbook.mechanics.ic.gates.world.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Chest;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.mechanics.ic.AbstractICFactory;
import com.sk89q.craftbook.mechanics.ic.AbstractSelfTriggeredIC;
import com.sk89q.craftbook.mechanics.ic.ChipState;
import com.sk89q.craftbook.mechanics.ic.IC;
import com.sk89q.craftbook.mechanics.ic.ICFactory;
import com.sk89q.craftbook.mechanics.pipe.PipeRequestEvent;
import com.sk89q.craftbook.util.ItemSyntax;
import com.sk89q.craftbook.util.ItemUtil;
import com.sk89q.craftbook.util.SignUtil;

/**
 * @author Me4502
 */
public class ContainerDispenser extends AbstractSelfTriggeredIC {

    public ContainerDispenser(Server server, ChangedSign sign, ICFactory factory) {

        super(server, sign, factory);
    }

    ItemStack item;
    int amount;

    @Override
    public void load() {

        String amountLine = getSign().getLine(2).trim();
        if (amountLine.equalsIgnoreCase("all") || amountLine.equals("*")) {
            // Toem hele beholderen per puls; alle stakke afgaar som EEN
            // samlet roer-forsendelse, saa intet behoever at ligge paa
            // jorden undervejs.
            amount = -1;
        } else {
            try {
                amount = Integer.parseInt(amountLine);
            } catch (Exception e) {
                amount = 1;
            }
        }

        item = ItemSyntax.getItem(getLine(3));
        if(item != null && amount > 0)
            item.setAmount(amount);
    }

    @Override
    public String getTitle() {

        return "Container Dispenser";
    }

    @Override
    public String getSignTitle() {

        return "CONTAINER DISPENSER";
    }

    @Override
    public void trigger(ChipState chip) {

        if (chip.getInput(0)) {
            chip.setOutput(0, dispense());
        }
    }

    @Override
    public void think(ChipState chip) {

        chip.setOutput(0, dispense());
    }

    /**
     * Returns true if the sign has water at the specified location.
     *
     * @return
     */
    protected boolean dispense() {

        Block b = getBackBlock();

        int x = b.getX();
        int y = b.getY() + 1;
        int z = b.getZ();
        Block bl = CraftBookBukkitUtil.toSign(getSign()).getBlock().getWorld().getBlockAt(x, y, z);
        Inventory inv = null;
        ArrayList<ItemStack> matching = new ArrayList<>();
        if (bl.getType() == Material.CHEST) {
            Chest c = (Chest) bl.getState();
            inv = c.getInventory();
            for (ItemStack it : c.getInventory().getContents()) {
                if (ItemUtil.isStackValid(it)) {
                    if(item == null || ItemUtil.areItemsIdentical(it, item)) {
                        matching.add(it);
                    }
                }
            }
        } else if (bl.getType() == Material.FURNACE) {
            Furnace c = (Furnace) bl.getState();
            inv = c.getInventory();
            ItemStack result = c.getInventory().getResult();
            if (ItemUtil.isStackValid(result)) {
                matching.add(result);
            }
        } else if (bl.getType() == Material.BREWING_STAND) {
            BrewingStand c = (BrewingStand) bl.getState();
            inv = c.getInventory();
            for (ItemStack it : c.getInventory().getContents()) {
                if (ItemUtil.isStackValid(it)) {
                    if (ItemUtil.areItemsIdentical(it, c.getInventory().getIngredient())) {
                        continue;
                    }
                    if(item == null || ItemUtil.areItemsIdentical(it, item)) {
                        matching.add(it);
                    }
                }
            }
        } else if (bl.getType() == Material.DISPENSER) {
            Dispenser c = (Dispenser) bl.getState();
            inv = c.getInventory();
            for (ItemStack it : c.getInventory().getContents()) {
                if (ItemUtil.isStackValid(it)) {
                    if(item == null || ItemUtil.areItemsIdentical(it, item)) {
                        matching.add(it);
                    }
                }
            }
        }

        if (inv == null || matching.isEmpty()) {
            return false;
        }
        if (amount < 0) {
            return dispenseAll(inv, matching);
        }
        return dispenseItem(inv, matching.get(0));
    }

    /**
     * "all"-tilstanden: alle matchende stakke fjernes fra beholderen og
     * afgaar som EEN roer-forsendelse (eller eet samlet drop ved skiltet,
     * hvis der ikke sidder et roer paa bagsiden). Fire clocks og eet item
     * ad gangen bliver til een puls og en tom kiste.
     */
    private boolean dispenseAll(Inventory inv, ArrayList<ItemStack> matching) {

        ArrayList<ItemStack> removed = new ArrayList<>();
        for (ItemStack stack : matching) {
            ItemStack take = stack.clone();
            HashMap<Integer, ItemStack> over = inv.removeItem(take.clone());
            if (over.isEmpty()) {
                removed.add(take);
            } else {
                int got = take.getAmount() - over.values().iterator().next().getAmount();
                if (got > 0) {
                    take.setAmount(got);
                    removed.add(take);
                }
            }
        }
        if (removed.isEmpty()) {
            return false;
        }

        BlockFace back = SignUtil.getBack(CraftBookBukkitUtil.toSign(getSign()).getBlock());
        Block pipe = getBackBlock().getRelative(back);

        PipeRequestEvent event = new PipeRequestEvent(pipe, new ArrayList<>(removed), getBackBlock());
        Bukkit.getPluginManager().callEvent(event);

        if(!event.isValid())
            return true;

        for (ItemStack stack : event.getItems())
            CraftBookBukkitUtil.toSign(getSign()).getWorld().dropItemNaturally(
                    CraftBookBukkitUtil.toSign(getSign()).getLocation(), stack);
        return true;
    }

    public boolean dispenseItem(Inventory inv, ItemStack old) {

        ItemStack item = old.clone();
        item.setAmount(amount);
        if (inv == null) return false;
        HashMap<Integer, ItemStack> over = inv.removeItem(item.clone());
        if (over.isEmpty()) {

            BlockFace back = SignUtil.getBack(CraftBookBukkitUtil.toSign(getSign()).getBlock());
            Block pipe = getBackBlock().getRelative(back);

            PipeRequestEvent event = new PipeRequestEvent(pipe, new ArrayList<>(Collections.singletonList(item.clone())), getBackBlock());
            Bukkit.getPluginManager().callEvent(event);

            if(!event.isValid())
                return true;

            for(ItemStack stack : event.getItems())
                CraftBookBukkitUtil.toSign(getSign()).getWorld().dropItemNaturally(CraftBookBukkitUtil.toSign(getSign()).getLocation(), stack);
            return true;
        } else {

            BlockFace back = SignUtil.getBack(CraftBookBukkitUtil.toSign(getSign()).getBlock());
            Block pipe = getBackBlock().getRelative(back);

            ItemStack leftover = new ArrayList<>(over.values()).get(0);
            int availableAmount = item.getAmount() - leftover.getAmount();
            if (availableAmount <= 0) {
                return false;
            }
            leftover.setAmount(availableAmount);

            PipeRequestEvent event = new PipeRequestEvent(pipe, new ArrayList<>(Collections.singletonList(leftover.clone())), getBackBlock());
            Bukkit.getPluginManager().callEvent(event);

            if(!event.isValid())
                return true;

            for (ItemStack it : event.getItems()) {

                if (item.getAmount() - it.getAmount() < 1) continue;
                CraftBookBukkitUtil.toSign(getSign()).getWorld().dropItemNaturally(
                        CraftBookBukkitUtil.toSign(getSign()).getLocation(), new ItemStack(it.getType(), item.getAmount() - it.getAmount(), it.getDurability()));
                return true;
            }
        }
        return false;
    }

    public static class Factory extends AbstractICFactory {

        public Factory(Server server) {

            super(server);
        }

        @Override
        public IC create(ChangedSign sign) {

            return new ContainerDispenser(getServer(), sign, this);
        }

        @Override
        public String getShortDescription() {

            return "Dispenses items out of containers.";
        }

        @Override
        public String[] getLineHelp() {

            return new String[] {"amount to dispense (or 'all')", null};
        }
    }
}
