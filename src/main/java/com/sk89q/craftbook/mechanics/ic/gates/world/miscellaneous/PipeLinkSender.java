package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import com.sk89q.craftbook.mechanics.ic.AbstractIC;
import com.sk89q.craftbook.mechanics.ic.AbstractICFactory;
import com.sk89q.craftbook.mechanics.ic.ChipState;
import com.sk89q.craftbook.mechanics.ic.IC;
import com.sk89q.craftbook.mechanics.ic.PipeInputIC;
import com.sk89q.craftbook.mechanics.pipe.PipePutEvent;
import org.bukkit.Server;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * MC1282 - Teleports items arriving by pipe to the bound PipeLink Receiver, where they
 * continue through the receiver's pipe network.
 */
public class PipeLinkSender extends AbstractIC implements PipeInputIC {

    public PipeLinkSender(Server server, ChangedSign sign, AbstractICFactory factory) {
        super(server, sign, factory);
    }

    @Override
    public String getTitle() {
        return "PipeLink Sender";
    }

    @Override
    public String getSignTitle() {
        return "PIPELINK_SENDER";
    }

    @Override
    public void trigger(ChipState chip) {
    }

    @Override
    public void load() {
        Sign s = CraftBookBukkitUtil.toSign(getSign());
        if (s == null)
            return;
        UUID rid = PipeLink.readBoundReceiverUUID(s);
        if (rid != null) {
            PipeLinkIndex.get().bindSender(s.getWorld().getUID(), s.getX(), s.getY(), s.getZ(), rid);
        }
    }

    @Override
    public void onPipeTransfer(PipePutEvent event) {
        Sign senderSign = CraftBookBukkitUtil.toSign(getSign());
        if (senderSign == null)
            return;
        UUID rid = PipeLinkIndex.get().getBoundReceiverAt(senderSign.getWorld().getUID(),
                PipeLinkIndex.posKey(senderSign.getX(), senderSign.getY(), senderSign.getZ()));
        if (rid == null) {
            rid = PipeLink.readBoundReceiverUUID(senderSign);
            if (rid == null)
                return;
            PipeLinkIndex.get().bindSender(senderSign.getWorld().getUID(), senderSign.getX(), senderSign.getY(), senderSign.getZ(), rid);
        }

        List<ItemStack> remaining = PipeLinkRouter.get().sendThroughLink(senderSign.getBlock(), rid, event.getItems(), null);
        if (remaining != null) {
            event.setItems(remaining);
        }
    }

    public static class Factory extends AbstractICFactory {

        public Factory(Server server) {
            super(server);
        }

        @Override
        public IC create(ChangedSign sign) {
            return new PipeLinkSender(getServer(), sign, this);
        }

        @Override
        public String getShortDescription() {
            return "Teleports incoming items to its bound receiver.";
        }

        @Override
        public String[] getLineHelp() {
            return new String[] {"Bind with Blaze Rod: receiver first, then sender.", null};
        }
    }
}
