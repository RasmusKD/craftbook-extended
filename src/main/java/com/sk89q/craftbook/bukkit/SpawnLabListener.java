package com.sk89q.craftbook.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Temporary measurement lab, only registered under the "spawnlab" debug flag: reproduces
 * GPFlags' NoMobSpawns mechanism (cancel CreatureSpawnEvent at LOWEST) so the cost of
 * cancelled natural spawns can be measured empirically. The "spawnlab.cancel" flag
 * cancels like GPFlags does (after the full spawn pipeline and entity construction);
 * "spawnlab.precancel" cancels in Paper's PreCreatureSpawnEvent instead (before entity
 * construction). Counters are logged every 10 seconds.
 */
public class SpawnLabListener implements Listener {

    private long naturalSeen, naturalCancelled, preSeen, preCancelled;
    // Spawns that actually landed inside the measurement box, counted at MONITOR after
    // every other plugin has had its say, so a cancelled one is not counted. Standing
    // mob counts move with wandering and despawning; this does not.
    private long inBoxSurvived;
    private volatile String mode = "none";

    public SpawnLabListener() {
        Bukkit.getScheduler().runTaskTimer(CraftBookPlugin.inst(), () -> {
            // Phase control without reloads: the mode file is polled every 10s.
            try {
                java.nio.file.Path f = CraftBookPlugin.inst().getDataFolder().toPath().resolve("spawnlab-mode.txt");
                mode = java.nio.file.Files.exists(f) ? java.nio.file.Files.readString(f).trim() : "none";
            } catch (Exception e) {
                mode = "none";
            }
            CraftBookPlugin.logger().info("[SpawnLab] 10s mode=" + mode + ": natural=" + naturalSeen
                    + " cancelled=" + naturalCancelled + " pre=" + preSeen + " preCancelled=" + preCancelled
                    + " inBox=" + inBoxSurvived
                    + backoffReport());
            naturalSeen = naturalCancelled = preSeen = preCancelled = inBoxSurvived = 0;
        }, 200L, 200L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL)
            return;
        naturalSeen++;
        if ("gpf".equals(mode)) {
            event.setCancelled(true);
            naturalCancelled++;
        }
    }

    /** Box is set from a file so a measurement run can move it without a rebuild. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnSurvived(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL)
            return;
        try {
            java.nio.file.Path f = CraftBookPlugin.inst().getDataFolder().toPath().resolve("spawnlab-box.txt");
            if (!java.nio.file.Files.exists(f)) return;
            String[] p = java.nio.file.Files.readString(f).trim().split("[ ,]+");
            org.bukkit.Location l = event.getLocation();
            if (!l.getWorld().getName().equals(p[0])) return;
            if (l.getBlockX() < Integer.parseInt(p[1]) || l.getBlockX() > Integer.parseInt(p[4])) return;
            if (l.getBlockY() < Integer.parseInt(p[2]) || l.getBlockY() > Integer.parseInt(p[5])) return;
            if (l.getBlockZ() < Integer.parseInt(p[3]) || l.getBlockZ() > Integer.parseInt(p[6])) return;
            inBoxSurvived++;
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreSpawn(com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent event) {
        if (event.getReason() != CreatureSpawnEvent.SpawnReason.NATURAL)
            return;
        preSeen++;
        if ("pre".equals(mode)) {
            event.setCancelled(true);
            preCancelled++;
        }
    }

    /**
     * Reads Paper's per player mob cap counters straight off the server player.
     *
     * mobCounts is what the player actually has near them; mobBackoffCounts is what
     * Paper adds on top for every cancelled PreCreatureSpawnEvent nearby, and
     * getMobCountNear returns the sum. Inferring the second one from how many mobs are
     * standing around is hopeless, because that number also moves with despawning and
     * wandering, so it is read directly. Reflection because it is an NMS field, and
     * best effort because a mapping change should degrade to no report, not an error.
     */
    private String backoffReport() {
        StringBuilder sb = new StringBuilder();
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            try {
                Object handle = p.getClass().getMethod("getHandle").invoke(p);
                int[] counts = (int[]) readField(handle, "mobCounts");
                int[] backoff = (int[]) readField(handle, "mobBackoffCounts");
                if (counts == null || backoff == null) continue;
                sb.append(" | ").append(p.getName())
                  .append(" monsters=").append(counts[0])
                  .append(" backoff=").append(backoff[0])
                  .append(" effective=").append(counts[0] + backoff[0]);
            } catch (Throwable ignored) {}
        }
        return sb.toString();
    }

    private static Object readField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
