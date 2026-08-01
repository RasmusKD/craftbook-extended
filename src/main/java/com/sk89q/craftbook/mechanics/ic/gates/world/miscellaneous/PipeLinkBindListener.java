package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.bukkit.CraftBookPlugin;
import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-game binding tool for PipeLink ICs.
 *
 * Sneak + click a receiver sign with a Blaze Rod to select it, then a sender sign to bind.
 * The selected receiver is remembered, so several senders can be bound in a row.
 * Sneak + left-click air with the Blaze Rod clears the remembered receiver.
 * Sneak + click any PipeLink sign with a Breeze Rod to inspect its bindings.
 */
public class PipeLinkBindListener implements Listener {

    private final Map<UUID, UUID> awaitingBind = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastReceiver = new ConcurrentHashMap<>();

    // Runs first and does not respect prior cancellation: other plugins (and air-click
    // events, which Bukkit fires pre-cancelled) would otherwise silently eat the click.
    // Claim safety is handled by our own PipeLinkProtection check.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND)
            return;
        Player p = e.getPlayer();
        if (!p.isSneaking())
            return;
        Material inHand = p.getInventory().getItemInMainHand().getType();
        if (inHand != Material.BLAZE_ROD && inHand != Material.BREEZE_ROD)
            return;

        Block clicked = e.getClickedBlock();
        if (clicked != null && SignUtil.isSign(clicked) && clicked.getState() instanceof Sign sign) {
            if (inHand == Material.BLAZE_ROD) {
                handleBlaze(p, sign);
            } else {
                handleInspect(p, sign);
            }
            e.setCancelled(true);
        } else if (e.getAction() == Action.LEFT_CLICK_AIR && inHand == Material.BLAZE_ROD) {
            lastReceiver.remove(p.getUniqueId());
            p.sendMessage(ChatColor.GRAY + "[Pipes] Husket receiver er ryddet.");
        }
    }

    private void handleBlaze(Player p, Sign clicked) {
        UUID pid = p.getUniqueId();

        if (isSenderIC(clicked) && lastReceiver.containsKey(pid)) {
            doBind(p, clicked, lastReceiver.get(pid));
            return;
        }

        if (!awaitingBind.containsKey(pid)) {
            if (!isReceiverIC(clicked)) {
                p.sendMessage(ChatColor.RED + "[Pipes] Klik først et Receiver-skilt ([MC1281]/PIPELINK_RECEIVER) med Blaze Rod.");
                return;
            }
            String denial = PipeLinkProtection.describeDenial(p, clicked.getBlock());
            if (denial != null) {
                p.sendMessage(ChatColor.RED + "[Pipes] Du mangler trust i " + denial + "s claim til at linke pipes (kræver /" + PipeLinkProtection.requiredTrustName() + ").");
                return;
            }
            UUID rid = PipeLink.readReceiverUUID(clicked);
            if (rid == null) {
                rid = UUID.randomUUID();
                PipeLink.writeReceiverUUID(clicked, rid);
            }
            PipeLinkIndex.get().registerReceiver(rid, clicked.getWorld().getUID(), clicked.getX(), clicked.getY(), clicked.getZ());
            lastReceiver.put(pid, rid);
            awaitingBind.put(pid, rid);
            p.sendMessage(ChatColor.GREEN + "[Pipes] Receiver valgt. Klik nu en Sender ([MC1282]/PIPELINK_SENDER).");
        } else if (!isSenderIC(clicked)) {
            p.sendMessage(ChatColor.RED + "[Pipes] Nu skal du klikke en Sender ([MC1282]/PIPELINK_SENDER).");
        } else {
            UUID rid = awaitingBind.remove(pid);
            if (rid == null) {
                p.sendMessage(ChatColor.RED + "[Pipes] Intern fejl: mangler receiver-valg. Prøv igen.");
                return;
            }
            doBind(p, clicked, rid);
        }
    }

    private void doBind(Player p, Sign senderSign, UUID rid) {
        String senderDenial = PipeLinkProtection.describeDenial(p, senderSign.getBlock());
        if (senderDenial != null) {
            p.sendMessage(ChatColor.RED + "[Pipes] Du mangler trust i " + senderDenial + "s claim til at linke pipes (kræver /" + PipeLinkProtection.requiredTrustName() + ").");
            return;
        }
        Block recvBlock = PipeLinkIndex.get().getReceiverSignBlock(rid);
        if (recvBlock != null) {
            String recvDenial = PipeLinkProtection.describeDenial(p, recvBlock);
            if (recvDenial != null) {
                p.sendMessage(ChatColor.RED + "[Pipes] Du mangler trust i " + recvDenial + "s claim (receiverens) til at linke til den (kræver /" + PipeLinkProtection.requiredTrustName() + ").");
                return;
            }
        }

        PipeLink.writeBoundReceiverUUID(senderSign, rid);
        PipeLinkIndex.get().bindSender(senderSign.getWorld().getUID(), senderSign.getX(), senderSign.getY(), senderSign.getZ(), rid);

        Block recv = PipeLinkIndex.get().getReceiverSignBlock(rid);
        if (recv != null) {
            sendLocationMessage(p, ChatColor.AQUA + "[Pipes] Sender bundet til receiver @ "
                    + recv.getWorld().getName() + " " + recv.getX() + " " + recv.getY() + " " + recv.getZ(), recv);
            drawLinkParticles(p, center(senderSign.getBlock()), center(recv));
        } else {
            p.sendMessage(ChatColor.YELLOW + "[Pipes] Sender bundet, men receiveren kan ikke findes i verden (forældet?).");
        }
    }

    private void handleInspect(Player p, Sign clicked) {
        if (isSenderIC(clicked)) {
            UUID rid = PipeLink.readBoundReceiverUUID(clicked);
            if (rid == null) {
                p.sendMessage(ChatColor.GOLD + "[Pipes] Denne sender er ikke bundet.");
                return;
            }
            Block recv = PipeLinkIndex.get().getReceiverSignBlock(rid);
            if (recv != null && SignUtil.isSign(recv)) {
                sendLocationMessage(p, ChatColor.GREEN + "[Pipes] Sender er bundet til receiver @ "
                        + recv.getWorld().getName() + " " + recv.getX() + " " + recv.getY() + " " + recv.getZ(), recv);
                drawLinkParticles(p, center(clicked.getBlock()), center(recv));
            } else {
                p.sendMessage(ChatColor.YELLOW + "[Pipes] Receiveren findes ikke (forældet link).");
            }
        } else if (isReceiverIC(clicked)) {
            UUID rid = PipeLink.readReceiverUUID(clicked);
            if (rid == null) {
                p.sendMessage(ChatColor.YELLOW + "[Pipes] Denne receiver har ingen ID endnu. Brug Blaze Rod (shift-klik) for at initialisere og binde en sender.");
                return;
            }
            if (PipeLinkIndex.get().getReceiverRef(rid) == null) {
                PipeLinkIndex.get().registerReceiver(rid, clicked.getWorld().getUID(), clicked.getX(), clicked.getY(), clicked.getZ());
            }

            Set<PipeLinkIndex.SenderRef> senders = PipeLinkIndex.get().getSendersFor(rid);
            List<PipeLinkIndex.SenderRef> sorted = new ArrayList<>(senders);
            sorted.sort(Comparator.<PipeLinkIndex.SenderRef, String>comparing(s -> {
                World w = Bukkit.getWorld(s.world());
                return w == null ? "" : w.getName();
            }).thenComparingInt(PipeLinkIndex.SenderRef::x)
              .thenComparingInt(PipeLinkIndex.SenderRef::y)
              .thenComparingInt(PipeLinkIndex.SenderRef::z));

            p.sendMessage(ChatColor.AQUA + "[Pipes] " + sorted.size() + " sender" + (sorted.size() == 1 ? "" : "e") + " bundet til denne receiver:");
            for (PipeLinkIndex.SenderRef sr : sorted) {
                World w = Bukkit.getWorld(sr.world());
                if (w == null)
                    continue;
                Block senderBlock = w.getBlockAt(sr.x(), sr.y(), sr.z());
                sendLocationMessage(p, ChatColor.GRAY + "- " + w.getName() + " " + sr.x() + " " + sr.y() + " " + sr.z(), senderBlock);
                drawLinkParticles(p, center(senderBlock), center(clicked.getBlock()));
            }
        } else {
            p.sendMessage(ChatColor.RED + "[Pipes] Dette er ikke et PipeLink-skilt.");
        }
    }

    public static boolean isReceiverIC(Sign s) {
        String l0 = norm(s.getLine(0));
        String l1 = norm(s.getLine(1));
        return containsAny(l0, "MC1281", "[MC1281]", "PIPELINK_RECEIVER", "PIPELINKRECEIVER")
                || containsAny(l1, "PIPELINK_RECEIVER", "PIPELINKRECEIVER", "RECEIVER");
    }

    public static boolean isSenderIC(Sign s) {
        String l0 = norm(s.getLine(0));
        String l1 = norm(s.getLine(1));
        return containsAny(l0, "MC1282", "[MC1282]", "PIPELINK_SENDER", "PIPELINKSENDER")
                || containsAny(l1, "PIPELINK_SENDER", "PIPELINKSENDER", "SENDER");
    }

    private static String norm(String s) {
        return s == null ? "" : ChatColor.stripColor(s).trim().toUpperCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n))
                return true;
        }
        return false;
    }

    private static Location center(Block b) {
        return new Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
    }

    /**
     * Sends a message describing a linked location. For players allowed to teleport, the
     * message is clickable and teleports them to the target (cross-dimension via execute).
     */
    private static void sendLocationMessage(Player p, String legacyText, Block target) {
        if (io.papermc.lib.PaperLib.isPaper() && p.hasPermission("minecraft.command.teleport")) {
            String cmd = "/minecraft:execute in " + target.getWorld().getKey() + " run teleport @s "
                    + (target.getX() + 0.5) + " " + (target.getY() + 0.5) + " " + (target.getZ() + 0.5);
            p.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(legacyText)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(cmd))
                    .hoverEvent(net.kyori.adventure.text.Component.text(
                            "Klik for at teleportere til " + target.getWorld().getName() + " " + target.getX() + " " + target.getY() + " " + target.getZ())));
        } else {
            p.sendMessage(legacyText);
        }
    }

    private static void drawLinkParticles(Player p, Location a, Location b) {
        final int points = 28;
        final double dx = (b.getX() - a.getX()) / points;
        final double dy = (b.getY() - a.getY()) / points;
        final double dz = (b.getZ() - a.getZ()) / points;
        new BukkitRunnable() {
            int t = 0;
            final Particle.DustOptions dust = new Particle.DustOptions(Color.AQUA, 1.2f);

            @Override
            public void run() {
                if (t >= points) {
                    cancel();
                    return;
                }
                p.spawnParticle(Particle.DUST, a.getX() + dx * t, a.getY() + dy * t, a.getZ() + dz * t, 1, dust);
                t++;
            }
        }.runTaskTimer(CraftBookPlugin.inst(), 0L, 1L);
    }
}
