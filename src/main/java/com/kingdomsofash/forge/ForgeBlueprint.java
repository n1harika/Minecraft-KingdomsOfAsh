package com.kingdomsofash.forge;

import com.kingdomsofash.persist.ForgeState;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lost Forge as a pure, deterministic function {@code (dx, dy, dz) -> block}, coordinates relative to the
 * centre of the floor. Because it is a function of the persisted plan only, generation is idempotent: a crash
 * mid-build is repaired by simply running the same blueprint again.
 *
 * <h3>Composition</h3>
 * <ul>
 *   <li><b>Hall</b>: a round chamber (radius R, domed ceiling, height H) of deepslate and blackstone.</li>
 *   <li><b>Floor</b>: weathered deepslate tiles with <b>crimson rune circuits</b> (outer ring, inner ring, eight
 *       spokes, eight glyphs) built from red nether bricks; they are the blocks that "light up" during the ritual.</li>
 *   <li><b>Dais</b>: a stepped blackstone plinth with the <b>altar anvil</b> at its heart, ringed by four standing
 *       lanterns. The dais is the strong visual centre; everything radiates from it.</li>
 *   <li><b>Pillars</b>: eight columns on the spoke axes, every third one intact (holds up the dome), the rest
 *       broken at different heights with rubble at their feet; bloodiron ore inlays on alternate columns.</li>
 *   <li><b>Ironwork</b>: four lava-cauldron braziers on blackstone posts, four lit blast-furnace alcoves in the
 *       wall flanked by iron bars, hanging chain-lanterns and a chandelier over the altar.</li>
 *   <li><b>Gate + stairway</b>: an arched gate leads to a lined stairway that climbs 1:1 to the surface, ending in
 *       a ruined blackstone gateway with lanterns.</li>
 * </ul>
 */
public final class ForgeBlueprint {
    public enum Mode { FORCE, FILL_IF_OPEN }

    public record Cell(String data, Mode mode) {
    }

    public static final String RUNE_DORMANT = "minecraft:red_nether_bricks";
    public static final String RUNE_ACTIVE = "minecraft:shroomlight";

    private static final Cell AIR = new Cell("minecraft:air", Mode.FORCE);
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    /** Flat cells at the top of the gate passage before the stairway starts to climb. */
    private static final int FLAT = 2;

    private final int r;
    private final int h;
    private final long seed;
    private final int dir;
    private final int stairLength;
    private final String decorOre;
    private final int[][] pillars; // px, pz, height, intact?1:0

    public ForgeBlueprint(ForgeState st, String decorOre) {
        this.r = st.radius;
        this.h = st.height;
        this.seed = st.seed;
        this.dir = Math.floorMod(st.dir, 4);
        this.stairLength = st.stairLength;
        this.decorOre = decorOre.toLowerCase().startsWith("minecraft:") ? decorOre : "minecraft:" + decorOre.toLowerCase();
        this.pillars = new int[8][4];
        double pr = r * 0.675;
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 4 * k;
            pillars[k][0] = (int) Math.round(pr * Math.cos(a));
            pillars[k][1] = (int) Math.round(pr * Math.sin(a));
            boolean intact = k % 3 == 0;
            pillars[k][3] = intact ? 1 : 0;
            pillars[k][2] = intact ? ceilingAt(pr) - 1 : 4 + (int) (mix(seed, k, 77, 5) % 6);
        }
    }

    // ------------------------------------------------------------------ public geometry helpers

    public int radius() {
        return r;
    }

    public int height() {
        return h;
    }

    /** Half-width of the square scan region around the centre (hall + walls + margin). */
    public int arenaExtent() {
        return r + 3;
    }

    public int stairLength() {
        return stairLength;
    }

    public int dirX() {
        return DIRS[dir][0];
    }

    public int dirZ() {
        return DIRS[dir][1];
    }

    /** Height of the stairway floor above the hall floor at step index i (0-based). */
    public int rise(int i) {
        return Math.max(0, i - FLAT);
    }

    /** dx of the stairway cell at step index i and lateral offset lat. */
    public int tunnelDx(int i, int lat) {
        int along = r + 3 + i;
        return along * DIRS[dir][0] - lat * DIRS[dir][1];
    }

    public int tunnelDz(int i, int lat) {
        int along = r + 3 + i;
        return along * DIRS[dir][1] + lat * DIRS[dir][0];
    }

    public int ceilingAt(double rad) {
        double q = Math.min(1.15, rad / r);
        return h - (int) Math.floor(q * q * 6);
    }

    public boolean insideHall(double dx, double dz) {
        return Math.hypot(dx, dz) <= r + 0.5;
    }

    // ------------------------------------------------------------------ runes

    private boolean floorRune(int dx, int dz) {
        double rad = Math.hypot(dx, dz);
        double outer = r - 2.0;
        double inner = r * 0.5;
        if (Math.abs(rad - outer) < 0.55) return true;
        if (Math.abs(rad - inner) < 0.55) return true;
        double ang = Math.atan2(dz, dx);
        // spokes on the 8 axes
        if (rad > 6.0 && rad < outer - 0.8) {
            for (int k = 0; k < 8; k++) {
                double a = Math.PI / 4 * k;
                double along = dx * Math.cos(a) + dz * Math.sin(a);
                double perp = Math.abs(-dx * Math.sin(a) + dz * Math.cos(a));
                if (along > 0 && perp < 0.5) return true;
            }
        }
        // glyphs (plus signs with a gap) between the spokes
        double gr = r * 0.725;
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 4 * k + Math.PI / 8;
            int gx = (int) Math.round(gr * Math.cos(a));
            int gz = (int) Math.round(gr * Math.sin(a));
            int ddx = Math.abs(dx - gx), ddz = Math.abs(dz - gz);
            if ((ddx + ddz == 1) || (ddx == 2 && ddz == 0) || (ddx == 0 && ddz == 2)) return true;
        }
        return false;
    }

    private boolean wallRune(int dx, int dy, int dz) {
        if (dy < 2 || dy > 9) return false;
        double rad = Math.hypot(dx, dz);
        if (rad <= r + 0.5 || rad > r + 1.6) return false;
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 4 * k + Math.PI / 8;
            double along = dx * Math.cos(a) + dz * Math.sin(a);
            double perp = Math.abs(-dx * Math.sin(a) + dz * Math.cos(a));
            if (along > 0 && perp < 0.55) return true;
        }
        return false;
    }

    /** All rune positions as {dx, dy, dz} - the circuitry that is lit during the ritual. */
    public List<int[]> runeCells() {
        List<int[]> out = new ArrayList<>();
        int e = r + 3;
        for (int dx = -e; dx <= e; dx++) {
            for (int dz = -e; dz <= e; dz++) {
                double rad = Math.hypot(dx, dz);
                if (rad <= r + 0.5 && rad > 5.5 && floorRune(dx, dz) && !inGateOrDais(dx, dz)) out.add(new int[]{dx, 0, dz});
                for (int dy = 2; dy <= 9; dy++) {
                    if (wallRune(dx, dy, dz) && !inGateCut(dx, dy, dz)) out.add(new int[]{dx, dy, dz});
                }
            }
        }
        return out;
    }

    private boolean inGateOrDais(int dx, int dz) {
        return Math.hypot(dx, dz) <= 5.5;
    }

    // ------------------------------------------------------------------ main function

    private int along(int dx, int dz) {
        return dx * DIRS[dir][0] + dz * DIRS[dir][1];
    }

    private int lat(int dx, int dz) {
        return -dx * DIRS[dir][1] + dz * DIRS[dir][0];
    }

    private boolean inGateCut(int dx, int dy, int dz) {
        int al = along(dx, dz), la = Math.abs(lat(dx, dz));
        return al >= r - 1 && al <= r + 2 && la <= 1 && dy >= 1 && dy <= 4;
    }

    /** @return the block for this cell, or null if the blueprint leaves it untouched. */
    public Cell cellAt(int dx, int dy, int dz) {
        int al = along(dx, dz);
        int la = lat(dx, dz);
        if (al >= r + 3 && Math.abs(la) <= 3) return tunnelCell(al - (r + 3), la, dy);
        double rad = Math.hypot(dx, dz);
        if (rad > r + 2.5 || dy < -3) return null;
        int ceil = ceilingAt(rad);

        // gate: opening, floor, frame
        if (al >= r - 1 && al <= r + 2) {
            int a = Math.abs(la);
            if (a <= 1 && dy >= 1 && dy <= 4) return AIR;
            if (a <= 1 && dy == 0) return f("minecraft:polished_blackstone_bricks");
            if (a == 2 && dy >= 0 && dy <= 5) return f(dy == 5 ? "minecraft:chiseled_polished_blackstone" : "minecraft:polished_blackstone_bricks");
            if (a <= 1 && dy == 5) return f("minecraft:polished_blackstone_bricks");
        }

        if (dy <= -1) return f(pickBricks(dx, dy, dz));

        if (rad <= r + 0.5) {
            if (dy == 0) return floor(dx, dz, rad);
            if (dy < ceil) return interior(dx, dy, dz, rad, ceil);
            if (dy < ceil + 2) return f(ceilingBlock(dx, dy, dz, rad));
            return null;
        }
        if (dy < ceil + 2) return wall(dx, dy, dz, rad);
        return null;
    }

    private Cell floor(int dx, int dz, double rad) {
        if (rad <= 5.5) return f(rad <= 1.5 ? "minecraft:chiseled_polished_blackstone" : "minecraft:polished_blackstone_bricks");
        if (floorRune(dx, dz)) return f(RUNE_DORMANT);
        long m = mix(seed, dx, dz, 1) % 100;
        if (m < 22) return f("minecraft:cracked_deepslate_tiles");
        if (m < 32) return f("minecraft:polished_deepslate");
        if (m < 37) return f("minecraft:blackstone");
        return f("minecraft:deepslate_tiles");
    }

    private String pickBricks(int dx, int dy, int dz) {
        long m = mix(seed, dx, dz, dy + 11) % 100;
        if (m < 18) return "minecraft:cracked_deepslate_bricks";
        if (m < 28) return "minecraft:deepslate_tiles";
        if (m < 34) return "minecraft:blackstone";
        return "minecraft:deepslate_bricks";
    }

    private String ceilingBlock(int dx, int dy, int dz, double rad) {
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 4 * k;
            double along = dx * Math.cos(a) + dz * Math.sin(a);
            double perp = Math.abs(-dx * Math.sin(a) + dz * Math.cos(a));
            if (along > 2 && perp < 0.6 && dy == ceilingAt(rad)) return "minecraft:chiseled_deepslate";
        }
        long m = mix(seed, dx, dz, dy + 3) % 100;
        if (m < 15) return "minecraft:cracked_deepslate_bricks";
        if (m < 22) return "minecraft:blackstone";
        return "minecraft:deepslate_bricks";
    }

    private Cell interior(int dx, int dy, int dz, double rad, int ceil) {
        // --- altar dais: base slab, plinth, anvil, four standing lanterns
        if (rad <= 4.5 && dy == 1) return f("minecraft:polished_blackstone_bricks");
        if (rad <= 2.6 && dy == 2) return f("minecraft:chiseled_polished_blackstone");
        if (dx == 0 && dz == 0 && dy == 3) return f("minecraft:anvil[facing=east]");
        if (dy == 3 && ((Math.abs(dx) == 2 && dz == 0) || (dx == 0 && Math.abs(dz) == 2))) return f("minecraft:lantern[hanging=false]");

        // --- chandelier over the altar
        if (dx == 0 && dz == 0) {
            if (dy >= ceil - 3) return f("minecraft:iron_chain[axis=y]");
            if (dy == ceil - 4) return f("minecraft:lantern[hanging=true]");
        }

        // --- pillars (plus-shaped, 3x3 base)
        for (int k = 0; k < 8; k++) {
            int px = pillars[k][0], pz = pillars[k][1], ph = pillars[k][2];
            boolean intact = pillars[k][3] == 1;
            int ddx = Math.abs(dx - px), ddz = Math.abs(dz - pz);
            if (ddx <= 1 && ddz <= 1) {
                boolean arm = ddx + ddz <= 1;
                if (dy <= 2 || arm) {
                    if (dy <= ph) {
                        if (dy == 1 || (intact && dy == ph)) return f("minecraft:chiseled_deepslate");
                        if (!intact && dy == ph) return f("minecraft:deepslate_brick_slab[type=bottom]");
                        if (k % 2 == 0 && (dy == 4 || dy == 5) && arm && !(ddx == 0 && ddz == 0)) return f(decorOre);
                        return f(mix(seed, dx, dz, dy) % 6 == 0 ? "minecraft:cracked_deepslate_bricks" : "minecraft:deepslate_bricks");
                    }
                }
            }
            if (!intact && dy == 1) {
                // rubble at the feet of broken columns
                for (int j = 0; j < 4; j++) {
                    int rx = px + (int) (mix(seed, k, j, 21) % 7) - 3;
                    int rz = pz + (int) (mix(seed, k, j, 22) % 7) - 3;
                    if (rx == dx && rz == dz && Math.abs(rx - px) + Math.abs(rz - pz) > 1) {
                        return f(j % 2 == 0 ? "minecraft:cobbled_deepslate" : "minecraft:deepslate_brick_slab[type=bottom]");
                    }
                }
            }
        }

        // --- lava-cauldron braziers on blackstone posts
        double br = r * 0.4;
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 4 + Math.PI / 2 * k;
            int bx = (int) Math.round(br * Math.cos(a)), bz = (int) Math.round(br * Math.sin(a));
            if (dx == bx && dz == bz) {
                if (dy == 1 || dy == 2) return f("minecraft:polished_blackstone_wall");
                if (dy == 3) return f("minecraft:lava_cauldron");
            }
        }

        // --- hanging chain lanterns near the wall
        double lr = r * 0.8;
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 4 * k + Math.PI / 8;
            int lx = (int) Math.round(lr * Math.cos(a)), lz = (int) Math.round(lr * Math.sin(a));
            if (dx == lx && dz == lz) {
                if (dy >= ceil - 3) return f("minecraft:iron_chain[axis=y]");
                if (dy == ceil - 4) return f("minecraft:lantern[hanging=true]");
            }
        }
        return AIR;
    }

    private Cell wall(int dx, int dy, int dz, double rad) {
        int ceil = ceilingAt(rad);
        if (dy >= ceil) return f(ceilingBlock(dx, dy, dz, rad));
        if (wallRune(dx, dy, dz)) return f(RUNE_DORMANT);
        // furnace alcoves on the diagonals, flanked by iron bars and chains
        if (rad <= r + 1.5) {
            for (int k = 0; k < 4; k++) {
                double a = Math.PI / 4 + Math.PI / 2 * k;
                double along = dx * Math.cos(a) + dz * Math.sin(a);
                double perp = Math.abs(-dx * Math.sin(a) + dz * Math.cos(a));
                if (along > r - 0.2 && perp < 0.75) {
                    if (dy == 1) return f("minecraft:blast_furnace[facing=" + facingToCentre(dx, dz) + ",lit=true]");
                    if (dy == 2 || dy == 3) return f("minecraft:iron_bars");
                    if (dy == 4) return f("minecraft:iron_chain[axis=y]");
                }
            }
        }
        return f(pickBricks(dx, dy, dz));
    }

    private static String facingToCentre(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? "west" : "east";
        return dz > 0 ? "north" : "south";
    }

    private static String facingName(int d) {
        return switch (d) {
            case 0 -> "east";
            case 1 -> "south";
            case 2 -> "west";
            default -> "north";
        };
    }

    // ------------------------------------------------------------------ stairway

    private Cell tunnelCell(int i, int lat, int dy) {
        if (i > stairLength + 3) return null;
        int rise = rise(i);
        int rel = dy - rise; // relative to this step's floor
        int a = Math.abs(lat);

        // ruined surface gateway: pillars either side, lintel, lanterns
        if (i >= stairLength - 1 && i <= stairLength + 1) {
            if (a == 2 && rel >= 0 && rel <= 5) {
                if (rel == 5) return f("minecraft:lantern[hanging=false]");
                return f(rel == 0 ? "minecraft:chiseled_polished_blackstone" : (rel == 3 ? RUNE_DORMANT : "minecraft:polished_blackstone_bricks"));
            }
            if (i == stairLength && a <= 2 && rel == 5) return f("minecraft:polished_blackstone_bricks");
        }

        if (a <= 1) {
            if (rel == 0) {
                if (i >= FLAT + 1) return f("minecraft:polished_blackstone_brick_stairs[facing=" + facingName(dir) + ",half=bottom]");
                return f("minecraft:polished_blackstone_bricks");
            }
            if (rel >= 1 && rel <= 4) {
                if (a == 0 && rel == 4 && i % 6 == 3 && i < stairLength - 2) return f("minecraft:lantern[hanging=true]");
                return AIR;
            }
            if (rel == -1) return fill("minecraft:deepslate_bricks");
            if (rel == 5 && i <= stairLength - 3) return fill("minecraft:deepslate_bricks");
            return null;
        }
        if (a == 2 && i <= stairLength - 1) {
            if (rel >= -1 && rel <= 5) return fill(rel == 2 && i % 6 == 3 ? RUNE_DORMANT : "minecraft:deepslate_bricks");
        }
        return null;
    }

    private static Cell f(String d) {
        return new Cell(d, Mode.FORCE);
    }

    private static Cell fill(String d) {
        return new Cell(d, Mode.FILL_IF_OPEN);
    }

    private static long mix(long seed, long a, long b, long c) {
        long z = seed + a * 0x9E3779B97F4A7C15L + b * 0xBF58476D1CE4E5B9L + c * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return z & Long.MAX_VALUE;
    }
}
