package com.kingdomsofash;

import com.kingdomsofash.combat.Mitigation;
import com.kingdomsofash.combat.RageModel;
import com.kingdomsofash.combat.RageModel.Change;
import com.kingdomsofash.combat.RuptureGeometry;
import com.kingdomsofash.ritual.WaveComposer;
import com.kingdomsofash.weapon.WeaponLedger;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PureLogicTest {

    // ---- Rage -------------------------------------------------------------------------------
    private static RageModel rage() {
        return new RageModel(2, 5, 2500, 1000);
    }

    @Test
    void everyTwoHitsGiveOneRage() {
        RageModel r = rage();
        assertEquals(Change.NONE, r.hit(0));
        assertEquals(0, r.rage());
        assertEquals(Change.GAINED, r.hit(100));
        assertEquals(1, r.rage());
        r.hit(200);
        r.hit(300);
        assertEquals(2, r.rage());
    }

    @Test
    void rageCapsAtFive() {
        RageModel r = rage();
        Change last = Change.NONE;
        for (int i = 0; i < 30; i++) {
            Change c = r.hit(i * 100L);
            if (c == Change.GAINED_MAX) last = c;
        }
        assertEquals(5, r.rage());
        assertEquals(Change.GAINED_MAX, last);
    }

    @Test
    void decayStartsAfterDelayThenOnePerSecond() {
        RageModel r = rage();
        for (int i = 0; i < 6; i++) r.hit(i * 100L); // 3 rage, last hit at 500
        assertEquals(3, r.rage());
        assertEquals(Change.NONE, r.tick(500 + 2499));
        assertEquals(Change.LOST, r.tick(500 + 2500));
        assertEquals(2, r.rage());
        assertEquals(Change.NONE, r.tick(500 + 3400));
        assertEquals(Change.LOST, r.tick(500 + 3500));
        assertEquals(1, r.rage());
    }

    @Test
    void hitDuringDecayStopsIt() {
        RageModel r = rage();
        for (int i = 0; i < 4; i++) r.hit(i * 100L);
        r.tick(300 + 2500);
        assertEquals(1, r.rage());
        r.hit(4000);
        assertFalse(r.decaying());
        assertEquals(Change.NONE, r.tick(4000 + 2000));
    }

    @Test
    void brokenChainLosesPartialProgress() {
        RageModel r = rage();
        r.hit(0);
        assertEquals(1, r.progress());
        r.hit(5000); // chain broken -> this is hit 1 of a new pair
        assertEquals(1, r.progress());
        assertEquals(0, r.rage());
    }

    @Test
    void spendRequiresEnough() {
        RageModel r = rage();
        for (int i = 0; i < 4; i++) r.hit(i);
        assertFalse(r.spend(3));
        assertEquals(2, r.rage());
        assertTrue(r.spend(2));
        assertEquals(0, r.rage());
    }

    // ---- Rupture geometry ---------------------------------------------------------------------
    // yaw 0 faces +Z.
    private static boolean hit(double x, double z, double hw) {
        return RuptureGeometry.hits(0, 64, 0, 0, 3, 6, 1.5, 2.5,
                x - hw, 64, z - hw, x + hw, 65.8, z + hw);
    }

    @Test
    void hitsInFront() {
        assertTrue(hit(0, 1.5, 0.3));
        assertTrue(hit(2.5, 2.5, 0.3));
        assertTrue(hit(-2.5, 2.5, 0.3));
    }

    @Test
    void missesBehindBeyondAndOutsideWidth() {
        assertFalse(hit(0, -1.0, 0.3));
        assertFalse(hit(0, 3.6, 0.3));
        assertFalse(hit(3.6, 1.5, 0.3));
        assertFalse(hit(-3.6, 1.5, 0.3));
    }

    @Test
    void usesBoundingBoxNotCenter() {
        assertTrue(hit(0, 3.4, 0.5)); // centre outside, box overlaps
    }

    @Test
    void verticalWindow() {
        assertFalse(RuptureGeometry.hits(0, 64, 0, 0, 3, 6, 1.5, 2.5, -0.3, 70, 1, 0.3, 71.8, 1.6));
        assertFalse(RuptureGeometry.hits(0, 64, 0, 0, 3, 6, 1.5, 2.5, -0.3, 60, 1, 0.3, 61.8, 1.6));
    }

    @Test
    void rotatedYaw() {
        // yaw 90 faces -X in Minecraft
        assertTrue(RuptureGeometry.hits(0, 64, 0, 90, 3, 6, 1.5, 2.5, -2.3, 64, -0.3, -1.7, 65.8, 0.3));
        assertFalse(RuptureGeometry.hits(0, 64, 0, 90, 3, 6, 1.5, 2.5, 1.7, 64, -0.3, 2.3, 65.8, 0.3));
        // diagonal corner of the rectangle is outside a 45 degree rotated box test point
        assertFalse(RuptureGeometry.hits(0, 64, 0, 45, 3, 6, 1.5, 2.5, 3.9, 64, 3.9, 4.2, 65.8, 4.2));
    }

    // ---- Mitigation ---------------------------------------------------------------------------
    @Test
    void furyTenPercentAfterMitigation() {
        double adj = Mitigation.furyAdjustment(10.0, 0.90);
        assertEquals(-1.0, adj, 1e-9);
        assertEquals(9.0, 10.0 + adj, 1e-9);
    }

    @Test
    void absorptionComesAfterReduction() {
        double pre = 10.0 + Mitigation.furyAdjustment(10.0, 0.9); // 9
        assertEquals(5.0, Mitigation.healthLoss(pre, 4.0), 1e-9);
        assertEquals(0.0, Mitigation.healthLoss(pre, 20.0), 1e-9);
    }

    // ---- Waves --------------------------------------------------------------------------------
    @Test
    void waveAlwaysExactlyFifteen() {
        Random rnd = new Random(7);
        double[][] w = {{60, 30, 10, 0}, {25, 25, 22, 28}};
        for (double[] weights : w) {
            for (int i = 0; i < 500; i++) {
                int[] c = WaveComposer.compose(weights, new int[]{99, 99, 5, 6}, 15, rnd);
                assertEquals(15, c[0] + c[1] + c[2] + c[3]);
                assertTrue(c[2] <= 5 && c[3] <= 6);
            }
        }
    }

    @Test
    void degenerateWeightsFallBackToZombies() {
        int[] c = WaveComposer.compose(new double[]{0, 0, 0, 0}, new int[]{99, 99, 99, 99}, 15, new Random(1));
        assertEquals(15, c[0]);
    }

    // ---- Ledger -------------------------------------------------------------------------------
    @Test
    void onlyFirstClaimWins() {
        WeaponLedger l = new WeaponLedger();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(l.beginReward(UUID.randomUUID(), 1));
        assertFalse(l.beginReward(UUID.randomUUID(), 2), "second weapon must never be minted");
        assertEquals(WeaponLedger.ClaimResult.CLAIMED, l.claim(a, "A", 10));
        assertEquals(WeaponLedger.ClaimResult.REJECTED, l.claim(b, "B", 11));
        assertTrue(l.isHeldBy(a));
        assertEquals(a.toString(), l.claimantId);
    }

    @Test
    void claimantSurvivesTransfers() {
        WeaponLedger l = new WeaponLedger();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        l.beginReward(UUID.randomUUID(), 1);
        l.claim(a, "A", 10);
        assertTrue(l.drop("world", 1, 2, 3, UUID.randomUUID(), 20, "death"));
        assertFalse(l.drop("world", 1, 2, 3, null, 21, "double event"));
        assertTrue(l.pickup(b, "B", 30));
        assertFalse(l.pickup(a, "A", 31), "already held");
        assertEquals("A", l.claimantName);
        assertEquals("B", l.holderName);
        assertTrue(l.isHeldBy(b));
    }

    @Test
    void recoveryInvalidatesOldCopies() {
        WeaponLedger l = new WeaponLedger();
        UUID a = UUID.randomUUID();
        l.beginReward(UUID.randomUUID(), 1);
        l.claim(a, "A", 10);
        UUID id = l.weaponUuid();
        long oldEpoch = l.epoch;
        assertTrue(l.isCurrent(id, oldEpoch));
        l.bumpEpoch(50, null, "admin", "test");
        assertFalse(l.isCurrent(id, oldEpoch));
        assertTrue(l.isCurrent(id, l.epoch));
    }

    @Test
    void adminAssignCreatesIdentityOnce() {
        WeaponLedger l = new WeaponLedger();
        UUID t = UUID.randomUUID();
        l.adminAssign(t, "T", UUID.randomUUID(), 1, "admin", "test");
        assertEquals(WeaponLedger.Phase.HELD, l.phase);
        assertFalse(l.beginReward(UUID.randomUUID(), 2));
        String id = l.weaponId;
        l.adminAssign(UUID.randomUUID(), "U", UUID.randomUUID(), 3, "admin", "again");
        assertEquals(id, l.weaponId, "identity is stable");
        assertEquals("T", l.claimantName);
    }
}
