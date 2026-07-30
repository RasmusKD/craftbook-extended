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
    public static final NamespacedKey RECV_UUID = new NamespacedKey("craftbook", "pipe_recv_uuid");

    private PipeLink() {
    }

    public static void writeReceiverUUID(Sign receiverSign, UUID id) {
        PersistentDataContainer pdc = receiverSign.getPersistentDataContainer();
        pdc.set(RECV_UUID, PersistentDataType.STRING, id.toString());
        receiverSign.update(true, false);
    }

    public static UUID readReceiverUUID(Sign receiverSign) {
        return readUUID(receiverSign.getPersistentDataContainer(), RECV_UUID);
    }

    public static void writeBoundReceiverUUID(Sign senderSign, UUID receiverId) {
        PersistentDataContainer pdc = senderSign.getPersistentDataContainer();
        pdc.set(SEND_BOUND, PersistentDataType.STRING, receiverId.toString());
        senderSign.update(true, false);
    }

    public static void clearBoundReceiverUUID(Sign senderSign) {
        PersistentDataContainer pdc = senderSign.getPersistentDataContainer();
        pdc.remove(SEND_BOUND);
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
