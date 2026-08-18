package vip.fubuki.playersync.util;

import java.util.function.IntUnaryOperator;

/**
 * Pure experience math used by the sync logic
 *
 * The per-level cost is injected as a function (vanilla's Player#getXpNeededForNextLevel
 * depends on the player's current level), so vanilla stays the single source of truth at
 * runtime while tests can use the known 1.20.1 formula.
 */
public final class ExperienceMath {

    private ExperienceMath() {
    }

    /** Result of distributing a total XP amount onto level + progress. */
    public record LevelAndProgress(int level, float progress) {
    }

    /**
     * Mirror of the cumulative-XP formulas used when storing a player
     * (see <a href="https://minecraft.wiki/w/Experience">the wiki</a>),
     * plus the partial progress into the current level.
     */
    public static int totalExperience(int level, float progress, int xpNeededForNextLevel) {
        int totalXp;

        // Calculate total XP for completed levels
        if (level > 30) {
            totalXp = (int) (4.5 * Math.pow(level, 2) - 162.5 * level + 2220);
        } else if (level > 15) {
            totalXp = (int) (2.5 * Math.pow(level, 2) - 40.5 * level + 360);
        } else {
            totalXp = level * level + 6 * level;
        }

        // Add partial level progress
        totalXp += Math.round(xpNeededForNextLevel * progress);

        return totalXp;
    }

    /**
     * Distributes a stored total XP amount onto (level, progress) by repeatedly paying the
     * per-level cost.
     *
     * Note: if the total is smaller than the cost of the first level, progress is reported
     * as 0 rather than the fraction.
     *
     * @param totalXp         total experience points read from the database
     * @param xpNeededAtLevel cost of the next level as a function of the current level
     */
    public static LevelAndProgress levelAndProgressFromTotalXp(int totalXp, IntUnaryOperator xpNeededAtLevel) {
        int level = 0;
        int remaining = totalXp;
        int xpForLevel;

        while (remaining >= (xpForLevel = xpNeededAtLevel.applyAsInt(level))) {
            remaining -= xpForLevel;
            level++;
        }

        float progress = level > 0
                ? (float) remaining / xpNeededAtLevel.applyAsInt(level)
                : 0f;

        return new LevelAndProgress(level, progress);
    }
}
