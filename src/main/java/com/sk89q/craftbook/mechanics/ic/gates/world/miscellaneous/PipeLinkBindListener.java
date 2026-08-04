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

    /**
     * The receiver a player last selected, with where it stood at selection time - so a
     * later bind can NAME it even after the sign is gone or its chunk evicted. Without
     * the position, "receiveren kan ikke findes" reads like nonsense on a freshly
     * placed sender: the player has no idea which old selection it refers to.
     */
    private record SelectedReceiver(UUID rid, UUID worldId, String world, int x, int y, int z) {
    }

    private static final Map<UUID, SelectedReceiver> lastReceiver = new ConcurrentHashMap<>();

    /**
     * Purges a receiver from every player's remembered selection. Called the moment a
     * receiver is KNOWN destroyed (sign broken, or a stale entry healed away because the
     * sign was replaced) - a blaze rod must not bind fresh senders to a ghost. A
     * receiver whose chunk merely unloaded is NOT purged; that selection still works.
     */
    static void forgetSelectionsOf(UUID rid) {
        lastReceiver.values().removeIf(sel -> sel.rid().equals(rid));
    }

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

        if (isReceiverIC(clicked)) {
            // Clicking a receiver always (re)selects it; sender clicks bind to the last
            // selected receiver, so re-clicking a sender after picking a new receiver
            // simply rebinds it.
            if (!hasLinkPermission(p, "mc1281")) {
                p.sendMessage(ChatColor.RED + "[Pipes] Du mangler permission til PipeLink-receivers (craftbook.ic.mc1281).");
                return;
            }
            if (!PipeLinkProtection.isWorldAllowed(clicked.getWorld())) {
                p.sendMessage(ChatColor.RED + "[Pipes] PipeLink er slået fra i denne verden.");
                return;
            }
            String denial = PipeLinkProtection.describeDenial(p, clicked.getBlock());
            if (denial != null) {
                p.sendMessage(ChatColor.RED + "[Pipes] Du mangler trust i " + denial + "s claim til at linke pipes (kræver /" + PipeLinkProtection.requiredTrustName() + ").");
                return;
            }
            UUID rid = PipeLink.readReceiverUUID(clicked);
            if (rid == null || !PipeLink.isReceiverEchoValid(clicked)) {
                // No identity yet, or a PDC cloned from another sign (WorldEdit paste):
                // mint a fresh one - a copy must never inherit the original's senders.
                rid = UUID.randomUUID();
                PipeLink.writeReceiverUUID(clicked, rid);
            } else if (!PipeLink.hasReceiverEcho(clicked)) {
                PipeLink.writeReceiverUUID(clicked, rid);
            }
            PipeLinkIndex.get().registerReceiver(rid, clicked.getWorld().getUID(), clicked.getX(), clicked.getY(), clicked.getZ());
            lastReceiver.put(pid, new SelectedReceiver(rid, clicked.getWorld().getUID(), clicked.getWorld().getName(), clicked.getX(), clicked.getY(), clicked.getZ()));
            p.sendMessage(ChatColor.GREEN + "[Pipes] Receiver valgt. Klik nu en eller flere Senders ([MC1282]).");
            return;
        }

        if (isSenderIC(clicked)) {
            if (!hasLinkPermission(p, "mc1282")) {
                p.sendMessage(ChatColor.RED + "[Pipes] Du mangler permission til PipeLink-senders (craftbook.ic.mc1282).");
                return;
            }
            SelectedReceiver sel = lastReceiver.get(pid);
            if (sel == null) {
                p.sendMessage(ChatColor.RED + "[Pipes] Klik først et Receiver-skilt ([MC1281]) med Blaze Rod.");
                return;
            }
            doBind(p, clicked, sel);
            return;
        }

        p.sendMessage(ChatColor.RED + "[Pipes] Dette er ikke et PipeLink-skilt.");
    }

    /**
     * The bind tool grants the capability the IC's sign-creation flow would have gated,
     * so it must honour the same permission nodes - sign text alone is not authorisation.
     */
    private static boolean hasLinkPermission(Player p, String icId) {
        return p.hasPermission("craftbook.ic." + icId) || p.hasPermission("craftbook.ic.safe." + icId);
    }

    private void doBind(Player p, Sign senderSign, SelectedReceiver sel) {
        UUID rid = sel.rid();
        if (!PipeLinkProtection.isWorldAllowed(senderSign.getWorld())) {
            p.sendMessage(ChatColor.RED + "[Pipes] PipeLink er slået fra i denne verden.");
            return;
        }
        String senderDenial = PipeLinkProtection.describeDenial(p, senderSign.getBlock());
        if (senderDenial != null) {
            p.sendMessage(ChatColor.RED + "[Pipes] Du mangler trust i " + senderDenial + "s claim til at linke pipes (kræver /" + PipeLinkProtection.requiredTrustName() + ").");
            return;
        }
        Block recvBlock = PipeLinkIndex.get().getReceiverSignBlock(rid);
        if (recvBlock != null) {
            String crossDenial = PipeLinkProtection.describeCrossLinkDenial(senderSign.getWorld(), recvBlock.getWorld());
            if (crossDenial != null) {
                p.sendMessage(ChatColor.RED + "[Pipes] Kan ikke linke: " + crossDenial + " — du kan stadig linke inden for samme verden.");
                return;
            }
        }
        if (recvBlock != null) {
            String recvDenial = PipeLinkProtection.describeDenial(p, recvBlock);
            if (recvDenial != null) {
                p.sendMessage(ChatColor.RED + "[Pipes] Du mangler trust i " + recvDenial + "s claim (receiverens) til at linke til den (kræver /" + PipeLinkProtection.requiredTrustName() + ").");
                return;
            }
        }

        UUID previous = PipeLink.readBoundReceiverUUID(senderSign);
        PipeLink.writeBoundReceiverUUID(senderSign, rid);
        // The sender itself remembers where its receiver stands, so the link survives
        // reboots without depending on the far chunk ever having been loaded.
        if (recvBlock != null)
            PipeLink.writeBoundReceiverPos(senderSign, recvBlock.getWorld().getUID(), recvBlock.getX(), recvBlock.getY(), recvBlock.getZ());
        else
            PipeLink.writeBoundReceiverPos(senderSign, sel.worldId(), sel.x(), sel.y(), sel.z());
        PipeLinkIndex.get().bindSender(senderSign.getWorld().getUID(), senderSign.getX(), senderSign.getY(), senderSign.getZ(), rid);

        String verb = previous == null ? "bundet" : previous.equals(rid) ? "allerede bundet" : "ombundet";
        Block recv = PipeLinkIndex.get().getReceiverSignBlock(rid);
        if (recv != null) {
            sendLocationMessage(p, ChatColor.AQUA + "[Pipes] Sender " + verb + " til receiver @ "
                    + recv.getWorld().getName() + " " + recv.getX() + " " + recv.getY() + " " + recv.getZ(), recv);
            drawLinkParticles(p, center(senderSign.getBlock()), center(recv));
        } else {
            p.sendMessage(ChatColor.YELLOW + "[Pipes] Sender bundet til receiveren du valgte @ " + sel.world() + " " + sel.x() + " " + sel.y() + " " + sel.z()
                    + " - den er ikke indlæst lige nu. Linket vågner når dens chunk loader; er skiltet fjernet, så vælg en ny receiver og bind om.");
        }
    }

    private void handleInspect(Player p, Sign clicked) {
        // Link topology (every bound sender's coordinates) is exactly the information a
        // claim protects; require the same trust to read it as to create it.
        String inspectDenial = PipeLinkProtection.describeDenial(p, clicked.getBlock());
        if (inspectDenial != null) {
            p.sendMessage(ChatColor.RED + "[Pipes] Du mangler trust i " + inspectDenial + "s claim til at inspicere links her (kræver /" + PipeLinkProtection.requiredTrustName() + ").");
            return;
        }
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
                String dormant = describeDormant(clicked.getBlock(), recv);
                if (dormant != null) {
                    p.sendMessage(ChatColor.YELLOW + "[Pipes] Linket er i dvale — der sendes ingen items: " + dormant + ".");
                }
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
                if (w == null) {
                    p.sendMessage(ChatColor.GRAY + "- " + sr.x() + " " + sr.y() + " " + sr.z()
                            + ChatColor.YELLOW + " (i en verden der ikke er indlæst)");
                    continue;
                }
                Block senderBlock = w.getBlockAt(sr.x(), sr.y(), sr.z());
                String dormant = describeDormant(senderBlock, clicked.getBlock());
                String suffix = dormant == null ? "" : ChatColor.YELLOW + " (i dvale: " + dormant + ")";
                sendLocationMessage(p, ChatColor.GRAY + "- " + w.getName() + " " + sr.x() + " " + sr.y() + " " + sr.z() + suffix, senderBlock);
                drawLinkParticles(p, center(senderBlock), center(clicked.getBlock()));
            }
        } else {
            p.sendMessage(ChatColor.RED + "[Pipes] Dette er ikke et PipeLink-skilt.");
        }
    }

    /**
     * Why an existing link is currently dormant (the router silently refuses delivery), or
     * null if it is live. Mirrors the checks in PipeLinkRouter.sendThroughLink so inspect
     * can explain what the router never has a player to tell.
     */
    private static String describeDormant(Block sender, Block receiver) {
        if (!PipeLinkProtection.isWorldAllowed(sender.getWorld()))
            return "PipeLink er slået fra i '" + sender.getWorld().getName() + "'";
        if (!PipeLinkProtection.isWorldAllowed(receiver.getWorld()))
            return "PipeLink er slået fra i '" + receiver.getWorld().getName() + "'";
        return PipeLinkProtection.describeCrossLinkDenial(sender.getWorld(), receiver.getWorld());
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
        // A line across the full span is unreadable at distance: a handful of points
        // spread over thousands of blocks, nearly all beyond render range. Draw a dense
        // beam from the end the PLAYER stands at, pointing toward the other end, so the
        // direction is visible no matter how far the link reaches. Short links get the
        // whole line as before, just denser.
        if (!a.getWorld().equals(b.getWorld()))
            return; // cross-dimension: no direction exists; the message carries the coords
        Location pl = p.getLocation();
        boolean fromA = a.distanceSquared(pl) <= b.distanceSquared(pl);
        Location from = fromA ? a : b;
        Location to = fromA ? b : a;
        double dist = from.distance(to);
        if (dist < 0.5)
            return;
        double ux = (to.getX() - from.getX()) / dist;
        double uy = (to.getY() - from.getY()) / dist;
        double uz = (to.getZ() - from.getZ()) / dist;
        double length = Math.min(dist, 24.0);

        Particle.DustOptions dust = new Particle.DustOptions(Color.AQUA, 1.2f);
        for (double d = 0; d <= length; d += 0.5) {
            p.spawnParticle(Particle.DUST, from.getX() + ux * d, from.getY() + uy * d, from.getZ() + uz * d, 1, dust);
        }
        // A travelling bright pulse toward the far end makes the direction unmistakable.
        final double beamLength = length;
        new BukkitRunnable() {
            double d = 0;
            final Particle.DustOptions bright = new Particle.DustOptions(Color.WHITE, 1.6f);

            @Override
            public void run() {
                if (d > beamLength || !p.isOnline()) {
                    cancel();
                    return;
                }
                p.spawnParticle(Particle.DUST, from.getX() + ux * d, from.getY() + uy * d, from.getZ() + uz * d, 1, bright);
                d += 1.2;
            }
        }.runTaskTimer(CraftBookPlugin.inst(), 0L, 1L);
    }
}
