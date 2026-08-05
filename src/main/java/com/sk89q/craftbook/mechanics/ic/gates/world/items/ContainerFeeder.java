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
        BlockFace parsed = parseDirection(getLine(2));
        direction = parsed == null ? BlockFace.DOWN : parsed; // hopper's default
        amount = 1;
        try {
            amount = Math.max(1, Math.min(64, Integer.parseInt(getLine(3).trim())));
        } catch (NumberFormatException ignored) {
        }
    }

    /** Null when the line is not a recognised direction. */
    private static BlockFace parseDirection(String line) {
        return switch (line.trim().toLowerCase(Locale.ROOT)) {
            case "up", "u", "op" -> BlockFace.UP;
            case "down", "d", "ned" -> BlockFace.DOWN;
            case "north", "n", "nord" -> BlockFace.NORTH;
            case "south", "s", "syd" -> BlockFace.SOUTH;
            case "east", "e", "oest", "øst" -> BlockFace.EAST;
            case "west", "w", "v", "vest" -> BlockFace.WEST;
            default -> null;
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
        // No setOutput: probing for an output lever costs more than the feed itself,
        // and a self-running feeder has no meaningful output signal. The redstone-
        // triggered variant still reports through trigger().
        feed();
    }

    /**
     * A think that moved nothing (full target, missing target, empty source) rests the
     * feeder briefly instead of rescanning both inventories every think - the same idea
     * as Paper's cooldown-when-full on hoppers, which retry every 8 game ticks when
     * blocked. IC instances are cached per location, so the state survives between
     * thinks; a cache eviction merely resets the rest, which is harmless.
     */
    private static final long IDLE_REST_MILLIS = 500L;
    private long restUntil;

    public boolean feed() {
        long now = System.currentTimeMillis();
        if (now < restUntil)
            return false;
        boolean moved = doFeed();
        if (!moved)
            restUntil = now + IDLE_REST_MILLIS;
        return moved;
    }

    private boolean doFeed() {
        Block source = getBackBlock();
        if (!InventoryUtil.doesBlockHaveInventory(source))
            return false;
        Block target = source.getRelative(direction);
        if (!InventoryUtil.doesBlockHaveInventory(target))
            return false;

        Inventory src = ((InventoryHolder) PaperLib.getBlockState(source, false).getState()).getInventory();
        InventoryHolder dst = (InventoryHolder) PaperLib.getBlockState(target, false).getState();

        for (int slot = 0; slot < src.getSize(); slot++) {
            ItemStack stack = src.getItem(slot);
            if (!ItemUtil.isStackValid(stack))
                continue;
            int take = Math.min(amount, stack.getAmount());
            ItemStack attempt = stack.clone();
            attempt.setAmount(take);
            // Insert first, then deduct only what actually fit: the deposit and the
            // withdrawal can never disagree, so a full target cannot dupe or destroy.
            // Same routing as pipe deliveries: smeltables and fuel land in the right
            // furnace slots, brewing stands sort ingredient/fuel/bottles, and shulker
            // boxes refuse nested shulkers.
            int rest = 0;
            for (ItemStack left : InventoryUtil.addItemsToInventory(dst, false, attempt))
                rest += left == null ? 0 : left.getAmount();
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

        // Write the effective defaults onto empty lines so the sign documents itself,
        // and reject typos instead of silently feeding downwards.
        @Override
        public void verify(ChangedSign sign) throws com.sk89q.craftbook.mechanics.ic.ICVerificationException {
            String line3 = sign.getLine(2).trim();
            if (line3.isEmpty())
                sign.setLine(2, "down");
            else if (parseDirection(line3) == null)
                throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException(
                        "Line 3 must be a direction: up, down, north, south, east or west.");
            String line4 = sign.getLine(3).trim();
            if (line4.isEmpty()) {
                sign.setLine(3, "1");
            } else {
                try {
                    int perTick = Integer.parseInt(line4);
                    if (perTick < 1 || perTick > 64)
                        throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException("Line 4 must be 1-64 items per tick.");
                } catch (NumberFormatException e) {
                    throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException("Line 4 must be a number: items per tick (1-64).");
                }
            }
        }
    }
}
