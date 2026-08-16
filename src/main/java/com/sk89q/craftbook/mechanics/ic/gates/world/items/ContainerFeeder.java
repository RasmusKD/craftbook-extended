package com.sk89q.craftbook.mechanics.ic.gates.world.items;

import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.mechanics.ic.AbstractICFactory;
import com.sk89q.craftbook.mechanics.ic.AbstractSelfTriggeredIC;
import com.sk89q.craftbook.mechanics.ic.ChipState;
import com.sk89q.craftbook.mechanics.ic.IC;
import com.sk89q.craftbook.mechanics.ic.ICFactory;
import com.sk89q.craftbook.util.InventoryUtil;
import com.sk89q.craftbook.util.SignUtil;
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
        direction = resolveDirection(getLine(2));
        if (com.sk89q.craftbook.bukkit.CraftBookPlugin.isDebugFlagEnabled("st.feeder")) {
            Block b = getBackBlock();
            com.sk89q.craftbook.bukkit.CraftBookPlugin.logger().info("[Feeder/DBG] load @" + b.getX() + "," + b.getY() + "," + b.getZ()
                    + " dir=" + direction + " line2='" + getLine(2) + "'");
        }
        // A whole stack per think is the efficient default: one addItem call costs
        // nearly the same regardless of size, so items-per-work is maximised. Players
        // write a smaller number on line 4 when they want a slower feed.
        amount = 64;
        try {
            amount = Math.max(1, Math.min(64, Integer.parseInt(getLine(3).trim())));
        } catch (NumberFormatException ignored) {
        }
    }

    /** Null when the line is not a recognised absolute direction. */
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

    private static boolean isRelativeDirection(String line) {
        return switch (line.trim().toLowerCase(Locale.ROOT)) {
            case "left", "l", "venstre", "right", "r", "hoejre", "højre" -> true;
            case "behind", "back", "forward", "frem", "bagved", "bagud" -> true;
            default -> false;
        };
    }

    /**
     * Left/right are from the perspective of the player reading the sign - the way you
     * describe your own build while standing in front of it. Calibrated empirically:
     * SignUtil's getLeft/getRight match the reader's hands (their javadoc is mirrored,
     * their implementation is not).
     */
    private BlockFace resolveDirection(String line) {
        Block signBlock = com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil.toSign(getSign()) != null
                ? com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil.toSign(getSign()).getBlock() : null;
        return resolveDirection(line, signBlock);
    }

    /** Same resolution for any sign block, so the loop check can read other feeders. */
    static BlockFace resolveDirection(String line, Block signBlock) {
        String token = line.trim().toLowerCase(Locale.ROOT);
        if (signBlock != null) {
            switch (token) {
                case "left", "l", "venstre":
                    return com.sk89q.craftbook.util.SignUtil.getLeft(signBlock);
                case "right", "r", "hoejre", "højre":
                    return com.sk89q.craftbook.util.SignUtil.getRight(signBlock);
                case "behind", "back", "forward", "frem", "bagved", "bagud":
                    // The far side of the container, straight through from the reader.
                    return com.sk89q.craftbook.util.SignUtil.getBack(signBlock);
                default:
                    break;
            }
        }
        BlockFace parsed = parseDirection(token);
        return parsed == null ? BlockFace.DOWN : parsed; // hopper's default
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
    private long debugIdleLogAt;
    private long debugMoveLogAt;

    public boolean feed() {
        long now = System.currentTimeMillis();
        if (now < restUntil)
            return false;
        boolean moved = doFeed();
        if (!moved) {
            restUntil = now + IDLE_REST_MILLIS;
            if (now > debugIdleLogAt && com.sk89q.craftbook.bukkit.CraftBookPlugin.isDebugFlagEnabled("st.feeder")) {
                debugIdleLogAt = now + 5000L;
                Block b = getBackBlock();
                boolean srcInv = InventoryUtil.doesBlockHaveInventory(b);
                Block t = b.getRelative(direction);
                int srcItems = 0;
                if (srcInv) {
                    for (ItemStack st : ((InventoryHolder) PaperLib.getBlockState(b, false).getState()).getInventory().getContents())
                        if (st != null) srcItems += st.getAmount();
                }
                com.sk89q.craftbook.bukkit.CraftBookPlugin.logger().info("[Feeder/DBG] idle @" + b.getX() + "," + b.getY() + "," + b.getZ()
                        + " dir=" + direction + " srcItems=" + srcItems + " targetInv=" + InventoryUtil.doesBlockHaveInventory(t));
            }
        }
        return moved;
    }

    /**
     * A ring of feeders moves items round forever with no net effect, and because every
     * feeder in it DOES move something, the idle rest never engages: it runs flat out and
     * looks frozen. Placement already refuses a sign that closes a ring, but a ring can
     * still appear without any sign being placed, by dropping in the container that joins
     * two chains, and rings built before this check existed are still out there.
     *
     * So the feeder also checks itself. The gate is cheap: a ring is only possible if the
     * target container carries a feeder of its own, which is a handful of block reads, and
     * only then is the chain walked. The answer is cached and revalidated on an interval,
     * because containers and signs change far more slowly than thinks happen.
     */
    private static final long LOOP_RECHECK_MILLIS = 30_000L;
    private long loopCheckedAt;
    private boolean feedsALoop;

    private boolean feedsALoop(Block source, Block target) {
        long now = System.currentTimeMillis();
        if (now < loopCheckedAt)
            return feedsALoop;
        loopCheckedAt = now + LOOP_RECHECK_MILLIS;
        feedsALoop = Factory.chainReturnsTo(source, target);
        if (feedsALoop && com.sk89q.craftbook.bukkit.CraftBookPlugin.isDebugFlagEnabled("st.feeder"))
            com.sk89q.craftbook.bukkit.CraftBookPlugin.logger().info("[Feeder] sleeping, feeds a loop @" + source.getX() + "," + source.getY() + "," + source.getZ());
        return feedsALoop;
    }

    private boolean doFeed() {
        Block source = getBackBlock();
        if (source.getType() == org.bukkit.Material.HOPPER) {
            // A hopper moves items on its own: a feeder on one double-moves and
            // keeps feeding while the hopper is redstone-locked, so the sign
            // pops off instead of quietly misbehaving. Placement is refused in
            // verify(); this catches signs that predate the rule or got a
            // hopper swapped in underneath.
            com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil.toSign(getSign()).getBlock().breakNaturally();
            return false;
        }
        if (!InventoryUtil.doesBlockHaveInventory(source))
            return false;
        Block target = source.getRelative(direction);
        if (!InventoryUtil.doesBlockHaveInventory(target))
            return false;
        if (feedsALoop(source, target))
            return false;

        io.papermc.lib.features.blockstatesnapshot.BlockStateSnapshotResult srcRes = PaperLib.getBlockState(source, false);
        io.papermc.lib.features.blockstatesnapshot.BlockStateSnapshotResult dstRes = PaperLib.getBlockState(target, false);
        org.bukkit.block.BlockState srcState = srcRes.getState();
        Inventory src = ((InventoryHolder) srcState).getInventory();
        InventoryHolder dst = (InventoryHolder) dstRes.getState();
        boolean dbg = System.currentTimeMillis() > debugMoveLogAt && com.sk89q.craftbook.bukkit.CraftBookPlugin.isDebugFlagEnabled("st.feeder");
        if (dbg) debugMoveLogAt = System.currentTimeMillis() + 5000L;

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
            if (dbg) {
                int srcTotal = 0;
                for (ItemStack st2 : src.getContents())
                    if (st2 != null) srcTotal += st2.getAmount();
                int dstTotal = 0;
                for (ItemStack st2 : dst.getInventory().getContents())
                    if (st2 != null) dstTotal += st2.getAmount();
                com.sk89q.craftbook.bukkit.CraftBookPlugin.logger().info("[Feeder/DBG] move @" + source.getX() + "," + source.getY() + "," + source.getZ()
                        + " slot=" + slot + " take=" + take + " rest=" + rest + " moved=" + moved
                        + " srcTotalAfterInsert=" + srcTotal + " dstTotal=" + dstTotal);
            }
            if (moved <= 0)
                continue; // no room for this item type; try the next stack
            if (moved >= stack.getAmount())
                src.setItem(slot, null);
            else {
                stack.setAmount(stack.getAmount() - moved);
                src.setItem(slot, stack);
            }
            InventoryUtil.syncDisplayedContainer(srcState);
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


        /**
         * One feeder per container. A second sign on the same chest or barrel does not
         * feed twice as fast, it just races the first one over the same stacks and makes
         * the output order impossible to reason about, so the second sign is refused at
         * placement rather than left to misbehave quietly.
         *
         * Only signs attached to the same container count. Two feeders on neighbouring
         * containers are fine, and a feeder whose target happens to be another feeder's
         * source is fine too, since that is a chain rather than a duplicate.
         */
        private static void rejectIfContainerAlreadyFed(ChangedSign sign)
                throws com.sk89q.craftbook.mechanics.ic.ICVerificationException {
            org.bukkit.block.Block signBlock = com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil.toSign(sign).getBlock();
            org.bukkit.block.Block container = SignUtil.getBackBlock(signBlock);
            if (container == null || !InventoryUtil.doesBlockHaveInventory(container))
                return;

            for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                    BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                org.bukkit.block.Block other = container.getRelative(face);
                if (other.equals(signBlock) || !SignUtil.isSign(other))
                    continue;
                // Only the sign hanging on THIS container, not one that merely sits nearby
                // on a different block.
                if (!container.equals(SignUtil.getBackBlock(other)))
                    continue;
                String id = com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil.toChangedSign(other).getLine(1).trim();
                if (id.equalsIgnoreCase("[MC1247]") || id.equalsIgnoreCase("[MC1247]S"))
                    throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException(
                            "Den beholder har allerede et feeder-skilt. Kun ét per kiste eller tønde.");
            }
        }


        /**
         * Refuses a feeder that would close a cycle. A ring of feeders (up, right, down,
         * left, back to the start) moves items round forever with no net effect, so it
         * costs a container-to-container transfer every think and produces nothing.
         *
         * Walking the chain is only unambiguous because a container may hold one feeder,
         * which rejectIfContainerAlreadyFed already guarantees. The walk is bounded, and
         * it stops at the first container without a feeder, so an ordinary chain is
         * cheap to check.
         */
        private static void rejectIfItClosesALoop(ChangedSign sign)
                throws com.sk89q.craftbook.mechanics.ic.ICVerificationException {
            org.bukkit.block.Block signBlock = com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil.toSign(sign).getBlock();
            org.bukkit.block.Block source = SignUtil.getBackBlock(signBlock);
            if (source == null || !InventoryUtil.doesBlockHaveInventory(source))
                return;

            BlockFace first = resolveDirection(sign.getLine(2), signBlock);
            if (chainReturnsTo(source, source.getRelative(first)))
                throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException(
                        "Det skilt ville lukke en ring af feeders. Items ville bare køre rundt i ring.");
        }

        /**
         * Whether following the feeders forward from {@code target} leads back to
         * {@code source}. Cheap in the ordinary case: a container with no feeder of its
         * own ends the walk immediately, so nothing is walked unless a chain exists.
         */
        static boolean chainReturnsTo(org.bukkit.block.Block source, org.bukkit.block.Block target) {
            org.bukkit.block.Block current = target;
            java.util.Set<String> seen = new java.util.HashSet<>();
            seen.add(key(source));

            for (int step = 0; step < MAX_CHAIN_WALK; step++) {
                // Never pull chunks in to run this check. A chain that leaves loaded
                // ground cannot be feeding anything right now anyway, and forcing the
                // world to load for it would cost more than the ring.
                if (!current.getWorld().isChunkLoaded(current.getX() >> 4, current.getZ() >> 4))
                    return false;
                if (!InventoryUtil.doesBlockHaveInventory(current))
                    return false;                 // chain ends in something that is not a container
                if (current.equals(source))
                    return true;
                if (!seen.add(key(current)))
                    return false;                 // a loop further along that does not include us
                org.bukkit.block.Block next = followFeeder(current);
                if (next == null)
                    return false;                 // no feeder on this container, chain ends
                current = next;
            }
            return false;
        }

        /**
         * Only a safety stop: the visited set already guarantees the walk terminates.
         * It is generous because the walk runs once, when a sign is placed, and a
         * player who builds a ring of hundreds of containers should still be told.
         */
        private static final int MAX_CHAIN_WALK = 512;

        private static String key(org.bukkit.block.Block b) {
            return b.getWorld().getName() + ':' + b.getX() + ',' + b.getY() + ',' + b.getZ();
        }

        /** The container this one feeds into, or null if it has no feeder sign. */
        private static org.bukkit.block.Block followFeeder(org.bukkit.block.Block container) {
            for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                    BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                org.bukkit.block.Block other = container.getRelative(face);
                if (!SignUtil.isSign(other) || !container.equals(SignUtil.getBackBlock(other)))
                    continue;
                ChangedSign cs = com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil.toChangedSign(other);
                String id = cs.getLine(1).trim();
                if (!id.equalsIgnoreCase("[MC1247]") && !id.equalsIgnoreCase("[MC1247]S"))
                    continue;
                return container.getRelative(resolveDirection(cs.getLine(2), other));
            }
            return null;
        }

        // Write the effective defaults onto empty lines so the sign documents itself,
        // and reject typos instead of silently feeding downwards.
        @Override
        public void verify(ChangedSign sign) throws com.sk89q.craftbook.mechanics.ic.ICVerificationException {
            org.bukkit.block.Block back = SignUtil.getBackBlock(
                    com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil.toSign(sign).getBlock());
            if (back != null && back.getType() == org.bukkit.Material.HOPPER)
                throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException(
                        "Feedere kan ikke sidde på en hopper. Hopperen flytter allerede selv items.");
            rejectIfContainerAlreadyFed(sign);
            String line3 = sign.getLine(2).trim();
            if (line3.isEmpty())
                sign.setLine(2, "down");
            else if (parseDirection(line3) == null && !isRelativeDirection(line3))
                throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException(
                        "Linje 3 skal være en retning: up, down, north, south, east, west, left, right eller behind.");
            String line4 = sign.getLine(3).trim();
            if (line4.isEmpty()) {
                sign.setLine(3, "64");
            } else {
                try {
                    int perTick = Integer.parseInt(line4);
                    if (perTick < 1 || perTick > 64)
                        throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException("Linje 4 skal være 1-64 items per tick.");
                } catch (NumberFormatException e) {
                    throw new com.sk89q.craftbook.mechanics.ic.ICVerificationException("Linje 4 skal være et tal: items per tick (1-64).");
                }
            }
            rejectIfItClosesALoop(sign);
        }
    }
}
