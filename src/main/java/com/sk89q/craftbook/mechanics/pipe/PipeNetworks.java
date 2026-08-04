package com.sk89q.craftbook.mechanics.pipe;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passive observability for pipe networks, and the /pipenetworks admin menu.
 *
 * Nothing here traverses anything: the BFS already visits every block of a network on
 * every pulse, so activity, size and throughput are recorded as a free side effect of
 * pulses that actually ran. A network's identity is the smallest packed position in its
 * traversal, so the same network pulsed from different pistons appears once. Networks
 * disappear from the list ten minutes after their last pulse - the menu deliberately
 * shows what runs NOW, not a map of everything ever built.
 */
public final class PipeNetworks implements Listener {

    private static final long FORGET_AFTER_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_PER_WORLD = 256;

    /** Why the last totally failed pulse failed; derived from PulseStats. */
    enum BlockReason {
        FULL("BLOKERET - containerne er fulde"),
        NO_OUTPUT("BLOKERET - intet output i nettet"),
        NO_CONTAINER("BLOKERET - output uden container"),
        FILTER("BLOKERET - ingen filtre matchede");

        final String text;

        BlockReason(String text) {
            this.text = text;
        }
    }

    static BlockReason classify(Pipes.PulseStats stats) {
        if (stats.outputs == 0)
            return BlockReason.NO_OUTPUT;
        if (stats.filterHits == 0)
            return BlockReason.FILTER;
        if (stats.attempts == 0)
            return BlockReason.NO_CONTAINER;
        return BlockReason.FULL;
    }

    static final class Net {
        long entryKey;
        /** Largest packed position of the last traversal - a real block at the "far end". */
        long maxKey;
        int size;
        long moved;
        long lastActive;
        boolean blocked;
        BlockReason reason = BlockReason.FULL;
    }

    private static final Map<UUID, Long2ObjectOpenHashMap<Net>> byWorld = new ConcurrentHashMap<>();

    private PipeNetworks() {
    }

    private static final PipeNetworks INSTANCE = new PipeNetworks();

    public static PipeNetworks get() {
        return INSTANCE;
    }

    /** Called from startPipe after a pulse that traversed; costs two map ops and a min-scan. */
    static void record(World world, LongOpenHashSet visited, long entryKey, int moved, boolean blocked, BlockReason reason) {
        if (visited.isEmpty())
            return;
        long netKey = Long.MAX_VALUE;
        long maxKey = Long.MIN_VALUE;
        LongIterator it = visited.iterator();
        while (it.hasNext()) {
            long k = it.nextLong();
            if (k < netKey)
                netKey = k;
            if (k > maxKey)
                maxKey = k;
        }
        Long2ObjectOpenHashMap<Net> nets = byWorld.computeIfAbsent(world.getUID(), w -> new Long2ObjectOpenHashMap<>());
        Net net = nets.get(netKey);
        if (net == null) {
            if (nets.size() >= MAX_PER_WORLD)
                trim(nets);
            net = new Net();
            nets.put(netKey, net);
        }
        net.entryKey = entryKey;
        net.maxKey = maxKey;
        net.size = Math.max(net.size, visited.size());
        net.moved += moved;
        net.lastActive = System.currentTimeMillis();
        if (blocked) {
            net.blocked = true;
            net.reason = reason;
        } else if (moved > 0) {
            net.blocked = false;
        }
    }

    private static void trim(Long2ObjectOpenHashMap<Net> nets) {
        long cutoff = System.currentTimeMillis() - FORGET_AFTER_MILLIS;
        nets.values().removeIf(n -> n.lastActive < cutoff);
        if (nets.size() >= MAX_PER_WORLD)
            nets.clear(); // pathological churn; start over rather than grow
    }

    /* ------------------------------------ menu ------------------------------------ */

    private static final class Menu implements InventoryHolder {
        final List<Location> slots = new ArrayList<>();
        Inventory inventory;
        int page;
        boolean hasPrev;
        boolean hasNext;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int PAGE_SIZE = 45; // top five rows; bottom row is navigation

    public void open(Player p) {
        open(p, 0);
    }

    public void open(Player p, int page) {
        long now = System.currentTimeMillis();
        record Row(UUID world, long netKey, Net net) {
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, Long2ObjectOpenHashMap<Net>> e : byWorld.entrySet()) {
            e.getValue().long2ObjectEntrySet().removeIf(en -> now - en.getValue().lastActive > FORGET_AFTER_MILLIS);
            for (Long2ObjectOpenHashMap.Entry<Net> en : e.getValue().long2ObjectEntrySet()) {
                rows.add(new Row(e.getKey(), en.getLongKey(), en.getValue()));
            }
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.net().lastActive).reversed());

        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages)
            page = pages - 1;
        if (page < 0)
            page = 0;
        boolean paginate = rows.size() > PAGE_SIZE;
        List<Row> shown = rows.subList(page * PAGE_SIZE, Math.min(rows.size(), (page + 1) * PAGE_SIZE));

        Menu menu = new Menu();
        menu.page = page;
        menu.hasPrev = page > 0;
        menu.hasNext = page < pages - 1;
        int size = paginate ? 54 : Math.min(54, ((Math.max(1, shown.size()) + 8) / 9) * 9);
        menu.inventory = Bukkit.createInventory(menu, size,
                ChatColor.DARK_AQUA + "Pipe-netværk (" + rows.size() + " aktive"
                + (paginate ? ", side " + (page + 1) + "/" + pages : "") + ")");

        for (Row row : shown) {
            if (menu.slots.size() >= PAGE_SIZE)
                break;
            Net net = row.net();
            World w = Bukkit.getWorld(row.world());
            if (w == null)
                continue;
            int x = unpackX(net.entryKey), y = unpackY(net.entryKey), z = unpackZ(net.entryKey);

            ItemStack icon = new ItemStack(net.blocked ? Material.CAMPFIRE : Material.STICKY_PISTON,
                    Math.max(1, Math.min(64, net.size)));
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName((net.blocked ? ChatColor.RED : ChatColor.AQUA)
                    + w.getName() + " " + x + " " + y + " " + z);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Størrelse: " + ChatColor.WHITE + "mindst " + net.size + " blokke");
            // The block list is not kept, but three real blocks of the network are known:
            // the pulsing piston and both extremes of the traversal. Probing all three
            // catches networks crossing claim borders without any per-pulse cost.
            java.util.LinkedHashSet<String> owners = new java.util.LinkedHashSet<>();
            boolean partlyUnclaimed = false;
            for (long probe : new long[] { net.entryKey, row.netKey(), net.maxKey }) {
                String owner = com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous.PipeLinkProtection
                        .claimOwnerAt(new Location(w, unpackX(probe), unpackY(probe), unpackZ(probe)));
                if (owner == null)
                    partlyUnclaimed = true;
                else
                    owners.add(owner);
            }
            if (!owners.isEmpty())
                lore.add(ChatColor.GRAY + (owners.size() > 1 ? "Claim-ejere: " : "Claim-ejer: ")
                        + ChatColor.WHITE + String.join(ChatColor.GRAY + ", " + ChatColor.WHITE, owners)
                        + (partlyUnclaimed ? ChatColor.DARK_GRAY + " (delvist i claim)" : ""));
            lore.add(ChatColor.GRAY + "Items flyttet: " + ChatColor.WHITE + net.moved
                    + ChatColor.DARK_GRAY + " (siden opstart)");
            lore.add(ChatColor.GRAY + "Sidste puls: " + ChatColor.WHITE + ago(now - net.lastActive));
            lore.add(net.blocked
                    ? ChatColor.RED + net.reason.text
                    : ChatColor.GREEN + "Kører");
            lore.add(ChatColor.YELLOW + "Klik for at teleportere");
            meta.setLore(lore);
            icon.setItemMeta(meta);

            menu.slots.add(new Location(w, x + 0.5, y + 1.0, z + 0.5));
            menu.inventory.addItem(icon);
        }

        if (paginate) {
            if (menu.hasPrev)
                menu.inventory.setItem(45, navItem(Material.ARROW, ChatColor.YELLOW + "Forrige side"));
            menu.inventory.setItem(49, navItem(Material.PAPER, ChatColor.GRAY + "Side " + (page + 1) + " af " + pages));
            if (menu.hasNext)
                menu.inventory.setItem(53, navItem(Material.ARROW, ChatColor.YELLOW + "Næste side"));
        }

        p.openInventory(menu.inventory);
    }

    private static ItemStack navItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private static String ago(long millis) {
        long s = millis / 1000;
        if (s < 60)
            return s + "s siden";
        return (s / 60) + "m " + (s % 60) + "s siden";
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu))
            return;
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (!(e.getWhoClicked() instanceof Player p))
            return;
        if (slot == 45 && menu.hasPrev) {
            Bukkit.getScheduler().runTask(com.sk89q.craftbook.bukkit.CraftBookPlugin.inst(), () -> open(p, menu.page - 1));
            return;
        }
        if (slot == 53 && menu.hasNext) {
            Bukkit.getScheduler().runTask(com.sk89q.craftbook.bukkit.CraftBookPlugin.inst(), () -> open(p, menu.page + 1));
            return;
        }
        if (slot < 0 || slot >= menu.slots.size())
            return;
        Location target = menu.slots.get(slot);
        // Never close or teleport inside the click event itself: the client still
        // believes the inventory is open and desyncs hard, worst on shift-clicks.
        Bukkit.getScheduler().runTask(com.sk89q.craftbook.bukkit.CraftBookPlugin.inst(), () -> {
            p.closeInventory();
            p.teleport(target);
            p.sendMessage(ChatColor.AQUA + "[Pipes] Teleporteret til netværket @ "
                    + target.getWorld().getName() + " " + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ());
        });
    }

    @EventHandler
    public void onMenuDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu)
            e.setCancelled(true);
    }

    /* ----------------------------------- command ----------------------------------- */

    public static final class NetworksCommand extends Command {
        public NetworksCommand() {
            super("pipenetworks", "Admin-menu over aktive pipe-netværk", "/pipenetworks", List.of("pnet"));
            setPermission("craftbook.pipes.networks");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Kun in-game.");
                return true;
            }
            if (!testPermission(p))
                return true;
            PipeNetworks.get().open(p);
            return true;
        }
    }

    private static int unpackX(long key) { return (int) (key >> 38); }
    private static int unpackZ(long key) { return (int) (key << 26 >> 38); }
    private static int unpackY(long key) { return (int) (key << 52 >> 52); }
}
