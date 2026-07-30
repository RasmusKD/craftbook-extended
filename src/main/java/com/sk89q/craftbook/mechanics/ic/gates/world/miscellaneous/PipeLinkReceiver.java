package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import com.sk89q.craftbook.mechanics.ic.AbstractIC;
import com.sk89q.craftbook.mechanics.ic.AbstractICFactory;
import com.sk89q.craftbook.mechanics.ic.ChipState;
import com.sk89q.craftbook.mechanics.ic.IC;
import org.bukkit.Server;
import org.bukkit.block.Sign;

import java.util.UUID;

/**
 * MC1281 - Destination point for PipeLink. Items sent by a bound PipeLink Sender are
 * injected into the pipe network behind this sign.
 *
 * Registration in the {@link PipeLinkIndex} is handled by chunk scanning; the IC lifecycle
 * only assigns the receiver its persistent UUID the first time it is created.
 */
public class PipeLinkReceiver extends AbstractIC {

    public PipeLinkReceiver(Server server, ChangedSign sign, AbstractICFactory factory) {
        super(server, sign, factory);
    }

    @Override
    public String getTitle() {
        return "PipeLink Receiver";
    }

    @Override
    public String getSignTitle() {
        return "PIPELINK_RECEIVER";
    }

    @Override
    public void trigger(ChipState chip) {
    }

    @Override
    public void load() {
        Sign sign = CraftBookBukkitUtil.toSign(getSign());
        if (sign == null)
            return;
        UUID id = PipeLink.readReceiverUUID(sign);
        if (id == null) {
            id = UUID.randomUUID();
            PipeLink.writeReceiverUUID(sign, id);
        }
        PipeLinkIndex.get().registerReceiver(id, sign.getWorld().getUID(), sign.getX(), sign.getY(), sign.getZ());
    }

    public static class Factory extends AbstractICFactory {

        public Factory(Server server) {
            super(server);
        }

        @Override
        public IC create(ChangedSign sign) {
            return new PipeLinkReceiver(getServer(), sign, this);
        }

        @Override
        public String getShortDescription() {
            return "Destination for linked PipeLink Sender ICs.";
        }

        @Override
        public String[] getLineHelp() {
            return new String[] {"Place this where items should arrive from a bound sender.", null};
        }
    }
}
