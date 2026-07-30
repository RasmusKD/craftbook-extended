package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * Claim-based access control for PipeLink binding, backed by GriefPrevention when it is
 * installed. Links are claim-scoped rather than owner-scoped: anyone with the configured
 * trust level in a claim can bind senders and receivers there, so people sharing a base
 * can link each other's pipes - while a plain build-trusted visitor cannot, unless the
 * server lowers the required level.
 *
 * Unclaimed wilderness is always allowed, and the whole check is skipped when
 * GriefPrevention is absent.
 */
public final class PipeLinkProtection {

    public enum Level {
        NONE, ACCESS, CONTAINER, BUILD, PERMISSION
    }

    private static volatile Level level = Level.PERMISSION;

    private PipeLinkProtection() {
    }

    public static void setLevel(String name) {
        try {
            level = Level.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[CraftBook] Unknown pipes link-protection level '" + name + "', using 'permission'.");
            level = Level.PERMISSION;
        }
    }

    public static Level getLevel() {
        return level;
    }

    /** Whether this player may create or modify a PipeLink binding at this sign block. */
    public static boolean mayLink(Player player, Block signBlock) {
        if (level == Level.NONE)
            return true;
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp == null || !gp.isEnabled())
            return true;
        return checkGriefPrevention(player, signBlock);
    }

    // Kept in its own method so GriefPrevention classes are only ever loaded when the
    // plugin is actually present.
    private static boolean checkGriefPrevention(Player player, Block block) {
        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(block.getLocation(), false, null);
        if (claim == null)
            return true;

        ClaimPermission required = switch (level) {
            case ACCESS -> ClaimPermission.Access;
            case CONTAINER -> ClaimPermission.Inventory;
            case BUILD -> ClaimPermission.Build;
            default -> ClaimPermission.Manage;
        };
        return claim.checkPermission(player, required, null) == null;
    }
}
