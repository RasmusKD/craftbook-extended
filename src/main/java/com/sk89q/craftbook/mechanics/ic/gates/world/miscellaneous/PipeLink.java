package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Persistent storage for PipeLink bindings. A receiver sign carries its own UUID, and a
 * sender sign carries the UUID of the receiver it is bound to. Both are stored in the
 * sign's PersistentDataContainer so bindings survive restarts without a separate database.
 */
public final class PipeLink {

    public static final NamespacedKey SEND_BOUND = new NamespacedKey("craftbook", "pipe_send_bound");
    public static final NamespacedKey SEND_RECV_POS = new NamespacedKey("craftbook", "pipe_send_recv_pos");
    public static final NamespacedKey RECV_UUID = new NamespacedKey("craftbook", "pipe_recv_uuid");
    public static final NamespacedKey RECV_POS = new NamespacedKey("craftbook", "pipe_recv_pos");

    private PipeLink() {
    }

    public static void writeReceiverUUID(Sign receiverSign, UUID id) {
        PersistentDataContainer pdc = receiverSign.getPersistentDataContainer();
        pdc.set(RECV_UUID, PersistentDataType.STRING, id.toString());
        pdc.set(RECV_POS, PersistentDataType.STRING, posEcho(receiverSign));
        receiverSign.update(true, false);
    }

    private static String posEcho(Sign sign) {
        return sign.getWorld().getUID() + ":" + sign.getX() + "," + sign.getY() + "," + sign.getZ();
    }

    /**
     * Whether the sign's stored position echo matches where it actually stands. WorldEdit
     * paste and structure blocks clone tile entities including the PDC, so only something
     * derived from the sign's own position can tell an original from a copy - a copy must
     * never inherit the original's identity and steal its senders. Signs without an echo
     * (bound before the echo existed) are accepted; callers backfill via
     * {@link #writeReceiverUUID}.
     */
    public static boolean isReceiverEchoValid(Sign sign) {
        String echo = sign.getPersistentDataContainer().get(RECV_POS, PersistentDataType.STRING);
        return echo == null || echo.equals(posEcho(sign));
    }

    public static boolean hasReceiverEcho(Sign sign) {
        return sign.getPersistentDataContainer().has(RECV_POS, PersistentDataType.STRING);
    }

    public static UUID readReceiverUUID(Sign receiverSign) {
        return readUUID(receiverSign.getPersistentDataContainer(), RECV_UUID);
    }

    public static void writeBoundReceiverUUID(Sign senderSign, UUID receiverId) {
        PersistentDataContainer pdc = senderSign.getPersistentDataContainer();
        pdc.set(SEND_BOUND, PersistentDataType.STRING, receiverId.toString());
        senderSign.update(true, false);
    }

    /**
     * The receiver's position, stored on the SENDER sign at bind time. This is what makes
     * a link as durable as two adjacent pipe blocks: after a reboot the index only knows
     * about chunks that have loaded, so without this a sender whose receiver chunk never
     * loaded again would be dead until someone happened to walk by the far end. With it,
     * the first pulse resolves the receiver straight from the sender's own sign.
     */
    public static void writeBoundReceiverPos(Sign senderSign, UUID world, int x, int y, int z) {
        PersistentDataContainer pdc = senderSign.getPersistentDataContainer();
        pdc.set(SEND_RECV_POS, PersistentDataType.STRING, world + ":" + x + "," + y + "," + z);
        senderSign.update(true, false);
    }

    public static boolean hasBoundReceiverPos(Sign senderSign) {
        return senderSign.getPersistentDataContainer().has(SEND_RECV_POS, PersistentDataType.STRING);
    }

    /** Returns {worldUUID, x, y, z} as Object[]{UUID, Integer, Integer, Integer}, or null. */
    public static Object[] readBoundReceiverPos(Sign senderSign) {
        String raw = senderSign.getPersistentDataContainer().get(SEND_RECV_POS, PersistentDataType.STRING);
        if (raw == null)
            return null;
        try {
            int colon = raw.indexOf(':');
            UUID world = UUID.fromString(raw.substring(0, colon));
            String[] parts = raw.substring(colon + 1).split(",");
            return new Object[] { world, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void clearBoundReceiverUUID(Sign senderSign) {
        PersistentDataContainer pdc = senderSign.getPersistentDataContainer();
        pdc.remove(SEND_BOUND);
        pdc.remove(SEND_RECV_POS);
        senderSign.update(true, false);
    }

    public static UUID readBoundReceiverUUID(Sign senderSign) {
        return readUUID(senderSign.getPersistentDataContainer(), SEND_BOUND);
    }

    private static UUID readUUID(PersistentDataContainer pdc, NamespacedKey key) {
        String raw = pdc.get(key, PersistentDataType.STRING);
        if (raw == null)
            return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void clearSenderUUIDAt(UUID world, int x, int y, int z) {
        World w = Bukkit.getWorld(world);
        if (w == null)
            return;
        Block b = w.getBlockAt(x, y, z);
        if (b.getState() instanceof Sign s && readBoundReceiverUUID(s) != null) {
            clearBoundReceiverUUID(s);
        }
    }
}
