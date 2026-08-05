package com.sk89q.craftbook.mechanics.ic.gates.world.items;

import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.mechanics.ic.AbstractICFactory;
import com.sk89q.craftbook.mechanics.ic.AbstractSelfTriggeredIC;
import com.sk89q.craftbook.mechanics.ic.ChipState;
import com.sk89q.craftbook.mechanics.ic.IC;
import com.sk89q.craftbook.mechanics.ic.ICFactory;
import com.sk89q.craftbook.util.InventoryUtil;
import com.sk89q.craftbook.util.ItemUtil;
import io.papermc.lib.PaperLib;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * MC1247 - Feeds items from the container behind the sign into the adjacent container
 * in a configurable direction. A hopper replacement without the part that makes
 * hoppers expensive: no entity pickup scan, no per-tick work when idle beyond one
 * inventory read, and it can feed any direction including straight up. Container to
 * container only - items never exist as entities, so nothing can be lost or picked up
 * in transit.
 */
public class ContainerFeeder extends AbstractSelfTriggeredIC {

    public ContainerFeeder(Server server, ChangedSign sign, ICFactory factory) {
        super(server, sign, factory);
    }

    private BlockFace direction;
    private int amount;

    @Override
    public void load() {
        direction = parseDirection(getLine(2));
        amount = 1;
        try {
            amount = Math.max(1, Math.min(64, Integer.parseInt(getLine(3).trim())));
        } catch (NumberFormatException ignored) {
        }
    }

    private static BlockFace parseDirection(String line) {
        return switch (line.trim().toLowerCase(Locale.ROOT)) {
            case "up", "u", "op" -> BlockFace.UP;
            case "north", "n", "nord" -> BlockFace.NORTH;
            case "south", "s", "syd" -> BlockFace.SOUTH;
            case "east", "e", "oest", "øst" -> BlockFace.EAST;
            case "west", "w", "v", "vest" -> BlockFace.WEST;
            default -> BlockFace.DOWN; // hopper's default
        };
    }

    @Override
    public String getTitle() {
        return "Container Feeder";
    }

    @Override
    public String getSignTitle() {
        return "FEEDER";
    }

    @Override
    public void trigger(ChipState chip) {
        if (chip.getInput(0)) chip.setOutput(0, feed());
    }

    @Override
    public void think(ChipState chip) {
        chip.setOutput(0, feed());
    }

    public boolean feed() {
        Block source = getBackBlock();
        if (!InventoryUtil.doesBlockHaveInventory(source))
            return false;
        Block target = source.getRelative(direction);
        if (!InventoryUtil.doesBlockHaveInventory(target))
            return false;

        Inventory src = ((InventoryHolder) PaperLib.getBlockState(source, false).getState()).getInventory();
        Inventory dst = ((InventoryHolder) PaperLib.getBlockState(target, false).getState()).getInventory();

        for (int slot = 0; slot < src.getSize(); slot++) {
            ItemStack stack = src.getItem(slot);
            if (!ItemUtil.isStackValid(stack))
                continue;
            int take = Math.min(amount, stack.getAmount());
            ItemStack attempt = stack.clone();
            attempt.setAmount(take);
            // Insert first, then deduct only what actually fit: the deposit and the
            // withdrawal can never disagree, so a full target cannot dupe or destroy.
            int rest = 0;
            for (ItemStack left : dst.addItem(attempt).values())
                rest += left.getAmount();
            int moved = take - rest;
            if (moved <= 0)
                continue; // no room for this item type; try the next stack
            if (moved >= stack.getAmount())
                src.setItem(slot, null);
            else {
                stack.setAmount(stack.getAmount() - moved);
                src.setItem(slot, stack);
            }
            return true;
        }
        return false;
    }

    public static class Factory extends AbstractICFactory {

        public Factory(Server server) {
            super(server);
        }

        @Override
        public IC create(ChangedSign sign) {
            return new ContainerFeeder(getServer(), sign, this);
        }

        @Override
        public String getShortDescription() {
            return "Feeds items from the container behind the sign into the neighbouring container in the given direction.";
        }

        @Override
        public String[] getLineHelp() {
            return new String[] {"direction: up/down/north/south/east/west", "items per tick (1-64)"};
        }
    }
}
