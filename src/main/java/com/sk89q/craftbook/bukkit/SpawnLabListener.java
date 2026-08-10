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
                    + " cancelled=" + naturalCancelled + " pre=" + preSeen + " preCancelled=" + preCancelled);
            naturalSeen = naturalCancelled = preSeen = preCancelled = 0;
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
}
