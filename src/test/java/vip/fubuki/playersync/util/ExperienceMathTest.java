package vip.fubuki.playersync.util;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests for the XP math used to store and restore player experience.
 *
 * The per-level cost function below is vanilla 1.20.1's
 * {@code Player#getXpNeededForNextLevel()}. Duplicating it here to let these tests
 * run without the game. If Mojang ever changes the formula,
 * the round-trip test against this copy still validates internal consistency of
 * store/restore, which is what player data integrity depends on.
 */
class ExperienceMathTest {

    /** Vanilla 1.20.1: xp required to go from {@code level} to {@code level + 1}. */
    private static final IntUnaryOperator VANILLA_XP_NEEDED = level -> {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        } else if (level >= 15) {
            return 37 + (level - 15) * 5;
        } else {
            return 7 + level * 2;
        }
    };

    // --- known anchor values from the wiki ---

    @Test
    void cumulativeXpMatchesKnownAnchors() {
        assertEquals(0, ExperienceMath.totalExperience(0, 0f, VANILLA_XP_NEEDED.applyAsInt(0)));
        assertEquals(7, ExperienceMath.totalExperience(1, 0f, VANILLA_XP_NEEDED.applyAsInt(1)));
        assertEquals(315, ExperienceMath.totalExperience(15, 0f, VANILLA_XP_NEEDED.applyAsInt(15)));
        assertEquals(352, ExperienceMath.totalExperience(16, 0f, VANILLA_XP_NEEDED.applyAsInt(16)));
        assertEquals(1395, ExperienceMath.totalExperience(30, 0f, VANILLA_XP_NEEDED.applyAsInt(30)));
        assertEquals(1507, ExperienceMath.totalExperience(31, 0f, VANILLA_XP_NEEDED.applyAsInt(31)));
    }

    @Test
    void quadraticFormulasAgreeWithSummedPerLevelCosts() {
        // The store side uses closed-form quadratics, the restore side sums per-level
        // costs. They must describe the same curve or XP drifts on every server switch.
        int summed = 0;
        for (int level = 0; level <= 100; level++) {
            assertEquals(summed, ExperienceMath.totalExperience(level, 0f, VANILLA_XP_NEEDED.applyAsInt(level)),
                    "cumulative XP mismatch at level " + level);
            summed += VANILLA_XP_NEEDED.applyAsInt(level);
        }
    }

    // --- restore: distributing a total onto level + progress ---

    @Test
    void distributesTotalsOntoLevelBoundaries() {
        assertEquals(new ExperienceMath.LevelAndProgress(0, 0f),
                ExperienceMath.levelAndProgressFromTotalXp(0, VANILLA_XP_NEEDED));
        assertEquals(new ExperienceMath.LevelAndProgress(1, 0f),
                ExperienceMath.levelAndProgressFromTotalXp(7, VANILLA_XP_NEEDED));
        assertEquals(new ExperienceMath.LevelAndProgress(30, 0f),
                ExperienceMath.levelAndProgressFromTotalXp(1395, VANILLA_XP_NEEDED));
    }

    @Test
    void partialProgressIsFractionOfNextLevel() {
        // 7 (level 1) + 4 of the 9 needed for level 2
        ExperienceMath.LevelAndProgress lp = ExperienceMath.levelAndProgressFromTotalXp(11, VANILLA_XP_NEEDED);
        assertEquals(1, lp.level());
        assertEquals(4f / 9f, lp.progress(), 1e-6);
    }

    @Test
    void xpBelowTheFirstLevelIsDroppedQuirk() {
        // Documents preserved legacy behavior: totals below the cost of level 1 report
        // progress 0, so 1-6 XP are lost on restore.
        // Do not "fix" this in a refactor commit, it changes what players see after a server switch.
        for (int xp = 1; xp < 7; xp++) {
            assertEquals(new ExperienceMath.LevelAndProgress(0, 0f),
                    ExperienceMath.levelAndProgressFromTotalXp(xp, VANILLA_XP_NEEDED),
                    "quirk changed for xp=" + xp);
        }
    }

    // --- the property that actually protects players: store(restore(x)) == x ---

    @Test
    void storeRestoreRoundTripIsStableWithinRounding() {
        // Above the level-0 quirk threshold, restoring a total and storing it again must
        // reproduce the same value (±1 for float progress rounding). Drift here means
        // players gain or lose XP on every server switch.
        for (int xp = 7; xp <= 20000; xp++) {
            ExperienceMath.LevelAndProgress lp = ExperienceMath.levelAndProgressFromTotalXp(xp, VANILLA_XP_NEEDED);
            int stored = ExperienceMath.totalExperience(lp.level(), lp.progress(),
                    VANILLA_XP_NEEDED.applyAsInt(lp.level()));
            assertTrue(Math.abs(stored - xp) <= 1,
                    "round trip drifted for xp=" + xp + ": got " + stored);
        }
    }

    @Test
    void roundTripIsIdempotentAfterOneCycle() {
        // Even where the first cycle rounds, a second store/restore cycle must be a fixed
        // point, otherwise XP keeps drifting on every subsequent server switch.
        for (int xp = 7; xp <= 20000; xp += 13) {
            ExperienceMath.LevelAndProgress lp1 = ExperienceMath.levelAndProgressFromTotalXp(xp, VANILLA_XP_NEEDED);
            int stored1 = ExperienceMath.totalExperience(lp1.level(), lp1.progress(),
                    VANILLA_XP_NEEDED.applyAsInt(lp1.level()));

            ExperienceMath.LevelAndProgress lp2 = ExperienceMath.levelAndProgressFromTotalXp(stored1, VANILLA_XP_NEEDED);
            int stored2 = ExperienceMath.totalExperience(lp2.level(), lp2.progress(),
                    VANILLA_XP_NEEDED.applyAsInt(lp2.level()));

            assertEquals(stored1, stored2, "round trip not a fixed point for xp=" + xp);
        }
    }
}
