package com.kingdomsofash;

import com.kingdomsofash.forge.ForgeBlueprint;
import com.kingdomsofash.persist.ForgeState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ForgeBlueprintTest {
    private static ForgeState plan(int dir) {
        ForgeState s = new ForgeState();
        s.radius = 20;
        s.height = 16;
        s.seed = 12345L;
        s.dir = dir;
        s.stairLength = 40;
        return s;
    }

    private static boolean passable(ForgeBlueprint.Cell c) {
        return c != null && (c.data().equals("minecraft:air") || c.data().startsWith("minecraft:lantern"));
    }

    @Test
    void deterministic() {
        ForgeBlueprint a = new ForgeBlueprint(plan(0), "NETHER_GOLD_ORE");
        ForgeBlueprint b = new ForgeBlueprint(plan(0), "NETHER_GOLD_ORE");
        for (int dx = -23; dx <= 23; dx += 3)
            for (int dz = -23; dz <= 23; dz += 3)
                for (int dy = -4; dy <= 20; dy++)
                    assertEquals(a.cellAt(dx, dy, dz), b.cellAt(dx, dy, dz));
    }

    @Test
    void altarAnvilAtCentreOfDais() {
        ForgeBlueprint bp = new ForgeBlueprint(plan(0), "NETHER_GOLD_ORE");
        assertTrue(bp.cellAt(0, 3, 0).data().startsWith("minecraft:anvil"));
        assertEquals("minecraft:chiseled_polished_blackstone", bp.cellAt(0, 2, 0).data());
        // free space for the reward to hover above the anvil
        assertTrue(passable(bp.cellAt(0, 4, 0)));
        assertTrue(passable(bp.cellAt(0, 5, 0)));
    }

    @Test
    void hallIsEnclosedAndWalkable() {
        ForgeBlueprint bp = new ForgeBlueprint(plan(1), "NETHER_GOLD_ORE");
        // a ring of floor, clear air above it
        for (int deg = 0; deg < 360; deg += 15) {
            int x = (int) Math.round(Math.cos(Math.toRadians(deg)) * 17.5);
            int z = (int) Math.round(Math.sin(Math.toRadians(deg)) * 17.5);
            assertNotNull(bp.cellAt(x, 0, z), "floor at " + deg);
            assertFalse(passable(bp.cellAt(x, 0, z)), "floor must be solid at " + deg);
        }
        // roof exists above the centre and the wall ring is closed (except the gate)
        assertNotEquals("minecraft:air", bp.cellAt(0, 16, 0).data());
        int walls = 0, checked = 0;
        for (int deg = 0; deg < 360; deg += 10) {
            int x = (int) Math.round(Math.cos(Math.toRadians(deg)) * 21.5);
            int z = (int) Math.round(Math.sin(Math.toRadians(deg)) * 21.5);
            checked++;
            var c = bp.cellAt(x, 3, z);
            if (c != null && !passable(c)) walls++;
        }
        assertTrue(walls >= checked - 3, "wall must be continuous apart from the gate");
    }

    @Test
    void gateAndStairwayAreConnected() {
        for (int dir = 0; dir < 4; dir++) {
            ForgeState st = plan(dir);
            ForgeBlueprint bp = new ForgeBlueprint(st, "NETHER_GOLD_ORE");
            // walk the centre line from the hall edge to the surface: 2 blocks of head clearance every step
            for (int i = 0; i <= st.stairLength; i++) {
                int rise = bp.rise(i);
                int dx = bp.tunnelDx(i, 0), dz = bp.tunnelDz(i, 0);
                assertNotNull(bp.cellAt(dx, rise, dz), "step floor " + i);
                assertFalse(passable(bp.cellAt(dx, rise, dz)), "step " + i + " must be solid floor");
                for (int h = 1; h <= 3; h++) assertTrue(passable(bp.cellAt(dx, rise + h, dz)), "clearance step " + i + " h" + h + " dir " + dir);
            }
            // gate cells between hall and stairway
            for (int along = st.radius - 1; along <= st.radius + 2; along++) {
                int dx = along * bp.dirX(), dz = along * bp.dirZ();
                for (int h = 1; h <= 3; h++) assertTrue(passable(bp.cellAt(dx, h, dz)), "gate along " + along);
            }
        }
    }

    @Test
    void runesExistAndAreFloorOrWall() {
        ForgeBlueprint bp = new ForgeBlueprint(plan(2), "NETHER_GOLD_ORE");
        var runes = bp.runeCells();
        assertTrue(runes.size() > 150, "expected substantial rune circuitry, got " + runes.size());
        for (int[] r : runes) assertEquals(ForgeBlueprint.RUNE_DORMANT, bp.cellAt(r[0], r[1], r[2]).data());
    }

    @Test
    void pillarsMixIntactAndBroken() {
        ForgeBlueprint bp = new ForgeBlueprint(plan(0), "NETHER_GOLD_ORE");
        int tall = 0, broken = 0;
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 4 * k;
            int px = (int) Math.round(13.5 * Math.cos(a)), pz = (int) Math.round(13.5 * Math.sin(a));
            if (bp.cellAt(px, 12, pz) != null && !passable(bp.cellAt(px, 12, pz))) tall++;
            else broken++;
        }
        assertTrue(tall >= 2 && broken >= 3, "tall=" + tall + " broken=" + broken);
    }
}
