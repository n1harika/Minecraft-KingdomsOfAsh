package com.kingdomsofash.ore;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.util.Keys;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;

import java.util.Random;

/**
 * Diamond-equivalent vein generator, run once for each newly generated chunk (see {@link OreListener}).
 *
 * <p>It mirrors the 1.18+ diamond features: 7 small veins (size 4, 50% air-exposure discard), 4 "buried" veins
 * (size 4, never touching air), and a 1-in-9 large vein (size 12, 70% discard); heights follow a triangle over
 * [-144, 16] with its peak at -64, and attempts that land below the world floor are discarded exactly like
 * vanilla (so only about half of the attempts materialise). Veins stay inside their own chunk so no neighbouring
 * chunk is ever force-loaded from the generator.</p>
 */
public final class OreGenerator {
    private final Settings s;
    private final BloodironService ore;

    public OreGenerator(Settings s, BloodironService ore) {
        this.s = s;
        this.ore = ore;
    }

    /** @return number of Bloodiron blocks created (0 if the chunk was already populated). */
    public int populate(Chunk c) {
        var pdc = c.getPersistentDataContainer();
        if (pdc.has(Keys.ORE_ITEM, PersistentDataType.BYTE)) return 0; // already populated
        pdc.set(Keys.ORE_ITEM, PersistentDataType.BYTE, (byte) 1);

        World w = c.getWorld();
        Random rnd = new Random(w.getSeed() * 31L ^ c.getX() * 341873128712L ^ c.getZ() * 132897987541L ^ 0x0B100D1A0EL);
        int[] packed = new int[512];
        int[] n = {0};

        for (int i = 0; i < s.oreSmallVeins; i++) vein(c, rnd, s.oreSmallSize, s.oreSmallDiscard, packed, n);
        for (int i = 0; i < s.oreBuriedVeins; i++) vein(c, rnd, s.oreBuriedSize, 1.0, packed, n);
        if (rnd.nextDouble() < s.oreLargeChance) vein(c, rnd, s.oreLargeSize, s.oreLargeDiscard, packed, n);

        if (n[0] > 0) ore.trackAll(c, packed, n[0]);
        return n[0];
    }

    private int triangleY(Random rnd) {
        double lo = s.oreVirtualMinY;
        double hi = s.oreMaxY;
        double t = (rnd.nextDouble() + rnd.nextDouble()) / 2.0; // triangular, peak at the middle
        return (int) Math.floor(lo + (hi - lo) * t);
    }

    private void vein(Chunk c, Random rnd, int size, double airDiscard, int[] packed, int[] n) {
        World w = c.getWorld();
        int cy = triangleY(rnd);
        if (cy < Math.max(s.oreMinY, w.getMinHeight()) || cy > Math.min(s.oreMaxY, w.getMaxHeight() - 1)) return; // vanilla discards these
        int cx = rnd.nextInt(16);
        int cz = rnd.nextInt(16);
        double radius = Math.cbrt(size) * 0.75 + 0.4;
        int placed = 0;
        for (int attempt = 0; attempt < size * 4 && placed < size; attempt++) {
            int x = cx + (int) Math.round(rnd.nextGaussian() * radius * 0.7);
            int y = cy + (int) Math.round(rnd.nextGaussian() * radius * 0.7);
            int z = cz + (int) Math.round(rnd.nextGaussian() * radius * 0.7);
            if (x < 0 || x > 15 || z < 0 || z > 15) continue;
            if (y < Math.max(s.oreMinY, w.getMinHeight()) || y > Math.min(s.oreMaxY, w.getMaxHeight() - 1)) continue;
            Block b = c.getBlock(x, y, z);
            if (!s.oreReplaceable.contains(b.getType())) continue;
            boolean skipAirCheck = airDiscard <= 0 || (airDiscard < 1 && rnd.nextFloat() >= airDiscard);
            if (!skipAirCheck && exposedToAir(c, x, y, z)) continue;
            b.setType(s.bloodironHost, false);
            if (n[0] < packed.length) packed[n[0]++] = BloodironService.packLocal(x, y, z);
            placed++;
        }
    }

    private boolean exposedToAir(Chunk c, int x, int y, int z) {
        World w = c.getWorld();
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            int nx = x + o[0], ny = y + o[1], nz = z + o[2];
            if (ny < w.getMinHeight() || ny >= w.getMaxHeight()) continue;
            Block nb;
            if (nx >= 0 && nx <= 15 && nz >= 0 && nz <= 15) {
                nb = c.getBlock(nx, ny, nz);
            } else {
                int ax = (c.getX() << 4) + nx, az = (c.getZ() << 4) + nz;
                if (!w.isChunkLoaded(ax >> 4, az >> 4)) continue; // unknown: treat as solid, never force a load
                nb = w.getBlockAt(ax, ny, az);
            }
            Material m = nb.getType();
            if (m.isAir() || m == Material.WATER || m == Material.LAVA || m == Material.CAVE_AIR) return true;
        }
        return false;
    }
}
