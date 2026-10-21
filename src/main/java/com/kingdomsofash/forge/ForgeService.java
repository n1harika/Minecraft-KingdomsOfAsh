package com.kingdomsofash.forge;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.persist.ForgeState;
import com.kingdomsofash.persist.StateStore;
import com.kingdomsofash.util.Audit;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Owns the single Lost Forge: locating a valid underground site inside +/-search-radius of world spawn,
 * committing the plan durably BEFORE building (so a crash resumes the same plan instead of creating a second
 * forge), building in throttled batches on the server thread, and answering geometry questions for protection,
 * the ritual and effects.
 */
public final class ForgeService {
    private final Plugin plugin;
    private final Settings s;
    private final StateStore store;
    private final Audit audit;

    private boolean busy; // locating or building: a second task can never start
    private BukkitTask buildTask;
    private final Set<Long> ticketed = new HashSet<>();
    private final Map<String, BlockData> dataCache = new HashMap<>();
    private ForgeBlueprint blueprintCache;
    private int blueprintRevision = -1;
    private List<int[]> runeCache;

    public ForgeService(Plugin plugin, Settings s, StateStore store, Audit audit) {
        this.plugin = plugin;
        this.s = s;
        this.store = store;
        this.audit = audit;
    }

    public ForgeState state() {
        return store.state().forge;
    }

    // ------------------------------------------------------------------ lifecycle

    public void bootstrap() {
        ForgeState st = state();
        switch (st.status) {
            case READY -> {
                if (world() == null) plugin.getLogger().warning("Lost Forge world '" + st.world + "' is not loaded; forge features are unavailable until it is.");
            }
            case GENERATING -> {
                plugin.getLogger().info("Resuming interrupted Lost Forge construction at " + describeLocation());
                startBuild();
            }
            case NONE -> {
                if (s.forgeAutoGenerate) locate(0);
                else plugin.getLogger().info("Lost Forge auto-generation is disabled; it will not exist until an admin runs /oathbreaker forge generate.");
            }
        }
    }

    public void shutdown() {
        if (buildTask != null) buildTask.cancel();
        releaseTickets();
        busy = false;
    }

    public boolean ready() {
        return state().ready() && world() != null;
    }

    public boolean busy() {
        return busy;
    }

    public World world() {
        String n = state().world;
        return n == null ? null : Bukkit.getWorld(n);
    }

    public ForgeBlueprint blueprint() {
        ForgeState st = state();
        if (blueprintCache == null || blueprintRevision != st.buildRevision) {
            blueprintCache = new ForgeBlueprint(st, s.bloodironHost.name());
            blueprintRevision = st.buildRevision;
            runeCache = null;
        }
        return blueprintCache;
    }

    public Location altarLocation() {
        World w = world();
        ForgeState st = state();
        if (w == null || !st.present()) return null;
        return new Location(w, st.altarX(), st.altarY(), st.altarZ());
    }

    /** Floor-level standing position next to the altar (for admin teleports). */
    public Location arenaCentre() {
        World w = world();
        ForgeState st = state();
        if (w == null || !st.present()) return null;
        return new Location(w, st.centerX + 0.5, st.floorY + 1, st.centerZ + 0.5);
    }

    public String describeLocation() {
        ForgeState st = state();
        return st.present() ? st.world + " " + st.centerX + " " + st.floorY + " " + st.centerZ : "(none)";
    }

    // ------------------------------------------------------------------ geometry queries

    /** The combat arena: everything inside the round hall. Used for eligibility and mob containment. */
    public boolean inArena(Location l) {
        if (!ready() || l.getWorld() == null || !l.getWorld().getName().equals(state().world)) return false;
        return inArena(l.getX(), l.getY(), l.getZ());
    }

    public boolean inArena(double x, double y, double z) {
        ForgeState st = state();
        double dx = x - (st.centerX + 0.5), dz = z - (st.centerZ + 0.5);
        double r = st.radius + 0.5;
        return dx * dx + dz * dz <= r * r && y >= st.floorY && y <= st.floorY + st.height;
    }

    /** True if the block belongs to the protected structure (hall, walls, dome, foundation, stairway, gateway). */
    public boolean isProtected(Block b) {
        if (!state().present() || !b.getWorld().getName().equals(state().world)) return false;
        return isProtected(b.getX(), b.getY(), b.getZ());
    }

    public boolean isProtected(int x, int y, int z) {
        ForgeState st = state();
        if (!st.present()) return false;
        double dx = x + 0.5 - (st.centerX + 0.5), dz = z + 0.5 - (st.centerZ + 0.5);
        double pr = st.radius + 3.5;
        if (dx * dx + dz * dz <= pr * pr && y >= st.floorY - 4 && y <= st.floorY + st.height + 3) return true;
        ForgeBlueprint bp = blueprint();
        int ddx = x - st.centerX, ddz = z - st.centerZ;
        int al = ddx * bp.dirX() + ddz * bp.dirZ();
        int la = -ddx * bp.dirZ() + ddz * bp.dirX();
        int i = al - (st.radius + 3);
        if (i >= 0 && i <= st.stairLength + 3 && Math.abs(la) <= 3) {
            int rise = bp.rise(Math.min(i, st.stairLength));
            return y >= st.floorY + rise - 3 && y <= st.floorY + rise + 9;
        }
        return false;
    }

    // ------------------------------------------------------------------ chunk helpers

    public void withChunkLoaded(Location l, Runnable r) {
        World w = l.getWorld();
        w.getChunkAtAsync(l).thenAccept(c -> Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "chunk callback failed", e);
            }
        }));
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** Keep the arena chunks loaded for the duration of a ritual (entities must keep ticking). */
    public void ticketArena() {
        World w = world();
        if (w == null) return;
        ForgeState st = state();
        int e = st.radius + 4;
        for (int cx = (st.centerX - e) >> 4; cx <= (st.centerX + e) >> 4; cx++) {
            for (int cz = (st.centerZ - e) >> 4; cz <= (st.centerZ + e) >> 4; cz++) {
                if (ticketed.add(key(cx, cz))) w.addPluginChunkTicket(cx, cz, plugin);
            }
        }
    }

    public void releaseTickets() {
        World w = world();
        if (w != null) {
            for (long k : ticketed) w.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
        }
        ticketed.clear();
    }

    // ------------------------------------------------------------------ locating

    /** Admin: generate (or relocate) when none exists / after clearing. */
    public boolean startLocateIfAbsent() {
        if (busy || state().present()) return false;
        locate(0);
        return true;
    }

    public void locate(int attempt) {
        World w = defaultWorld();
        if (w == null) {
            plugin.getLogger().warning("No overworld available for the Lost Forge; will retry in 30s");
            Bukkit.getScheduler().runTaskLater(plugin, () -> locate(attempt), 600L);
            return;
        }
        if (attempt >= s.forgeMaxAttempts) {
            plugin.getLogger().severe("Could not find a valid site for the Lost Forge in " + attempt + " attempts. "
                    + "Use /oathbreaker forge generate to retry.");
            busy = false;
            return;
        }
        busy = true;
        Location spawn = w.getSpawnLocation();
        Random rnd = new Random();
        int range = s.forgeSearchRadius;
        int cx, cz;
        int guard = 0;
        do {
            cx = spawn.getBlockX() + rnd.nextInt(range * 2 + 1) - range;
            cz = spawn.getBlockZ() + rnd.nextInt(range * 2 + 1) - range;
            guard++;
        } while (guard < 50 && (Math.max(Math.abs(cx - spawn.getBlockX()), Math.abs(cz - spawn.getBlockZ())) < s.forgeMinDistance
                || !w.getWorldBorder().isInside(new Location(w, cx, 64, cz))));
        final int fx = cx, fz = cz;
        int ext = s.arenaRadius + 6;
        loadChunks(w, fx - ext, fz - ext, fx + ext, fz + ext, () -> evaluate(w, fx, fz, attempt));
    }

    private World defaultWorld() {
        if (s.forgeWorld != null && !s.forgeWorld.isBlank()) return Bukkit.getWorld(s.forgeWorld);
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.NORMAL) return w;
        return null;
    }

    private void loadChunks(World w, int minX, int minZ, int maxX, int maxZ, Runnable done) {
        List<CompletableFuture<?>> fs = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) fs.add(w.getChunkAtAsync(cx, cz, true));
        }
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) {
                        plugin.getLogger().log(Level.WARNING, "chunk load for forge site search failed", ex);
                        busy = false;
                        Bukkit.getScheduler().runTaskLater(plugin, () -> locate(0), 200L);
                        return;
                    }
                    try {
                        done.run();
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(Level.SEVERE, "forge site evaluation crashed", e);
                        busy = false;
                    }
                }));
    }

    private int top(World w, int x, int z) {
        return w.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
    }

    private boolean wet(World w, int x, int z) {
        int y = top(w, x, z);
        Material m = w.getBlockAt(x, y + 1, z).getType();
        Material t = w.getBlockAt(x, y, z).getType();
        return m == Material.WATER || m == Material.LAVA || t == Material.ICE || t == Material.PACKED_ICE || t == Material.BLUE_ICE
                || w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) > y + 1 && w.getBlockAt(x, y + 2, z).isLiquid();
    }

    private void evaluate(World w, int cx, int cz, int attempt) {
        int ext = s.arenaRadius + 4;
        int minSurface = Integer.MAX_VALUE;
        for (int dx = -ext; dx <= ext; dx += 6) {
            for (int dz = -ext; dz <= ext; dz += 6) {
                int x = cx + dx, z = cz + dz;
                if (wet(w, x, z)) {
                    locate(attempt + 1);
                    return;
                }
                minSurface = Math.min(minSurface, top(w, x, z));
            }
        }
        long seed = w.getSeed() ^ (cx * 31L + cz * 17L) ^ System.nanoTime();
        Random rnd = new Random(seed);
        int depth = 40 + rnd.nextInt(26);
        int floorY = Math.min(minSurface - s.arenaHeight - 3 - s.forgeMinRockCover, minSurface - depth);
        floorY = Math.max(floorY, w.getMinHeight() + 12);
        if (minSurface - (floorY + s.arenaHeight + 2) < s.forgeMinRockCover) {
            locate(attempt + 1);
            return;
        }
        // sample the volume: too much lava / water means a bad site (the shell seals small leaks)
        int lava = 0, water = 0;
        for (int i = 0; i < 160; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, rr = Math.sqrt(rnd.nextDouble()) * s.arenaRadius;
            int x = cx + (int) Math.round(Math.cos(a) * rr), z = cz + (int) Math.round(Math.sin(a) * rr);
            int y = floorY + rnd.nextInt(s.arenaHeight);
            Material m = w.getBlockAt(x, y, z).getType();
            if (m == Material.LAVA) lava++;
            else if (m == Material.WATER) water++;
        }
        if (lava > 6 || water > 12) {
            locate(attempt + 1);
            return;
        }
        ForgeState plan = new ForgeState();
        plan.world = w.getName();
        plan.centerX = cx;
        plan.centerZ = cz;
        plan.floorY = floorY;
        plan.radius = s.arenaRadius;
        plan.height = s.arenaHeight;
        plan.seed = seed;
        tryDirections(w, plan, minSurface, attempt, new ArrayList<>(List.of(0, 1, 2, 3)), rnd);
    }

    private void tryDirections(World w, ForgeState plan, int minSurface, int attempt, List<Integer> dirs, Random rnd) {
        if (dirs.isEmpty()) {
            locate(attempt + 1);
            return;
        }
        Collections.shuffle(dirs, rnd);
        int d = dirs.remove(0);
        plan.dir = d;
        ForgeBlueprint bp = new ForgeBlueprint(plan, "minecraft:stone");
        int maxStair = 130;
        int x0 = plan.centerX + bp.tunnelDx(0, 0), z0 = plan.centerZ + bp.tunnelDz(0, 0);
        int x1 = plan.centerX + bp.tunnelDx(maxStair, 0), z1 = plan.centerZ + bp.tunnelDz(maxStair, 0);
        loadChunks(w, Math.min(x0, x1) - 4, Math.min(z0, z1) - 4, Math.max(x0, x1) + 4, Math.max(z0, z1) + 4, () -> {
            int len = -1;
            for (int i = 0; i <= maxStair; i++) {
                int x = plan.centerX + bp.tunnelDx(i, 0), z = plan.centerZ + bp.tunnelDz(i, 0);
                if (plan.floorY + bp.rise(i) + 5 >= top(w, x, z) + 1) {
                    len = i;
                    break;
                }
            }
            boolean ok = len >= 14;
            if (ok) {
                for (int lat = -3; lat <= 3 && ok; lat++) {
                    int x = plan.centerX + bp.tunnelDx(len, lat), z = plan.centerZ + bp.tunnelDz(len, lat);
                    if (wet(w, x, z)) ok = false;
                }
            }
            if (!ok) {
                tryDirections(w, plan, minSurface, attempt, dirs, rnd);
                return;
            }
            plan.stairLength = len;
            plan.surfaceY = plan.floorY + bp.rise(len);
            commitPlan(plan);
        });
    }

    private void commitPlan(ForgeState plan) {
        ForgeState st = state();
        if (st.present()) { // someone else got there first (admin race): never create a second forge
            busy = false;
            return;
        }
        st.status = ForgeState.Status.GENERATING;
        st.world = plan.world;
        st.centerX = plan.centerX;
        st.centerZ = plan.centerZ;
        st.floorY = plan.floorY;
        st.radius = plan.radius;
        st.height = plan.height;
        st.seed = plan.seed;
        st.dir = plan.dir;
        st.stairLength = plan.stairLength;
        st.surfaceY = plan.surfaceY;
        st.plannedAtMs = System.currentTimeMillis();
        st.buildRevision++;
        store.saveNow();
        audit.log("FORGE", "Lost Forge planned at " + describeLocation() + " (stairway " + st.stairLength + " blocks, entrance dir " + st.dir
                + ", entrance surface " + entranceX() + " " + st.surfaceY + " " + entranceZ() + ")");
        startBuild();
    }

    public int entranceX() {
        ForgeBlueprint bp = blueprint();
        return state().centerX + bp.tunnelDx(state().stairLength, 0);
    }

    public int entranceZ() {
        ForgeBlueprint bp = blueprint();
        return state().centerZ + bp.tunnelDz(state().stairLength, 0);
    }

    // ------------------------------------------------------------------ building

    /** Admin: rebuild the structure at the same location (idempotent). */
    public boolean rebuild() {
        if (busy || !state().present()) return false;
        state().status = ForgeState.Status.GENERATING;
        state().buildRevision++;
        store.saveNow();
        startBuild();
        return true;
    }

    private void startBuild() {
        if (buildTask != null) return;
        World w = world();
        ForgeState st = state();
        if (w == null) {
            plugin.getLogger().warning("Forge world '" + st.world + "' not loaded; build postponed");
            busy = false;
            return;
        }
        busy = true;
        ForgeBlueprint bp = blueprint();
        int e = bp.arenaExtent();
        int minX = st.centerX - e, maxX = st.centerX + e, minZ = st.centerZ - e, maxZ = st.centerZ + e;
        int tx0 = st.centerX + bp.tunnelDx(0, 0), tz0 = st.centerZ + bp.tunnelDz(0, 0);
        int tx1 = st.centerX + bp.tunnelDx(st.stairLength + 3, 0), tz1 = st.centerZ + bp.tunnelDz(st.stairLength + 3, 0);
        minX = Math.min(minX, Math.min(tx0, tx1) - 4);
        maxX = Math.max(maxX, Math.max(tx0, tx1) + 4);
        minZ = Math.min(minZ, Math.min(tz0, tz1) - 4);
        maxZ = Math.max(maxZ, Math.max(tz0, tz1) + 4);
        final int fminX = minX, fmaxX = maxX, fminZ = minZ, fmaxZ = maxZ;
        for (int cx = fminX >> 4; cx <= fmaxX >> 4; cx++) {
            for (int cz = fminZ >> 4; cz <= fmaxZ >> 4; cz++) {
                if (ticketed.add(key(cx, cz))) w.addPluginChunkTicket(cx, cz, plugin);
            }
        }
        loadChunks(w, fminX, fminZ, fmaxX, fmaxZ, () -> compileAndRun(w, bp, st));
    }

    private void compileAndRun(World w, ForgeBlueprint bp, ForgeState st) {
        List<int[]> pos = new ArrayList<>(60000);
        List<ForgeBlueprint.Cell> cells = new ArrayList<>(60000);
        int e = bp.arenaExtent();
        for (int dx = -e; dx <= e; dx++) {
            for (int dz = -e; dz <= e; dz++) {
                for (int dy = -4; dy <= bp.height() + 4; dy++) {
                    ForgeBlueprint.Cell c = bp.cellAt(dx, dy, dz);
                    if (c != null && !isTunnelDomain(bp, dx, dz)) {
                        pos.add(new int[]{st.centerX + dx, st.floorY + dy, st.centerZ + dz});
                        cells.add(c);
                    }
                }
            }
        }
        for (int i = 0; i <= st.stairLength + 3; i++) {
            int rise = bp.rise(Math.min(i, st.stairLength));
            for (int lat = -3; lat <= 3; lat++) {
                int dx = bp.tunnelDx(i, lat), dz = bp.tunnelDz(i, lat);
                for (int dy = rise - 3; dy <= rise + 9; dy++) {
                    ForgeBlueprint.Cell c = bp.cellAt(dx, dy, dz);
                    if (c != null) {
                        pos.add(new int[]{st.centerX + dx, st.floorY + dy, st.centerZ + dz});
                        cells.add(c);
                    }
                }
            }
        }
        // carve first (air), walls / shell afterwards, so lining never gets removed by a later carve
        List<Integer> order = new ArrayList<>(pos.size());
        for (int i = 0; i < pos.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt(i -> cells.get(i).data().equals("minecraft:air") ? 0 : 1));
        final int total = order.size();
        final int[] cursor = {0};
        final int perTick = s.forgeBlocksPerTick;
        audit.log("FORGE", "building " + total + " blocks (" + perTick + "/tick)");
        buildTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                int end = Math.min(total, cursor[0] + perTick);
                for (; cursor[0] < end; cursor[0]++) {
                    int idx = order.get(cursor[0]);
                    apply(w, pos.get(idx), cells.get(idx));
                }
                if (cursor[0] >= total) {
                    finishBuild();
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, "forge build failed; will resume on next start", ex);
                buildTask.cancel();
                buildTask = null;
                busy = false;
            }
        }, 1L, 1L);
    }

    private boolean isTunnelDomain(ForgeBlueprint bp, int dx, int dz) {
        int al = dx * bp.dirX() + dz * bp.dirZ();
        int la = -dx * bp.dirZ() + dz * bp.dirX();
        return al >= bp.radius() + 3 && Math.abs(la) <= 3;
    }

    private void apply(World w, int[] p, ForgeBlueprint.Cell c) {
        BlockData data = dataCache.computeIfAbsent(c.data(), k -> {
            try {
                return Bukkit.createBlockData(k);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("forge: unknown block data '" + k + "', using stone bricks");
                return Bukkit.createBlockData(Material.STONE_BRICKS);
            }
        });
        Block b = w.getBlockAt(p[0], p[1], p[2]);
        if (c.mode() == ForgeBlueprint.Mode.FILL_IF_OPEN && b.getType().isSolid()) return;
        if (b.getBlockData().equals(data)) return;
        b.setBlockData(data, false);
    }

    private void finishBuild() {
        buildTask.cancel();
        buildTask = null;
        ForgeState st = state();
        st.status = ForgeState.Status.READY;
        st.builtAtMs = System.currentTimeMillis();
        store.saveNow();
        busy = false;
        releaseTickets();
        audit.log("FORGE", "Lost Forge complete at " + describeLocation() + "; entrance at " + entranceX() + " " + st.surfaceY + " " + entranceZ());
        plugin.getLogger().info("The Lost Forge has been built at " + describeLocation() + " (entrance " + entranceX() + ", " + st.surfaceY + ", " + entranceZ() + ")");
    }

    // ------------------------------------------------------------------ runes

    public List<int[]> runes() {
        blueprint();
        if (runeCache == null) {
            runeCache = new ArrayList<>(blueprint().runeCells());
            runeCache.sort(Comparator.comparingDouble(c -> Math.hypot(c[0], c[2])));
        }
        return runeCache;
    }

    /**
     * Lights or dims the rune circuitry as a ripple travelling outward from the altar over {@code spreadTicks}.
     */
    public void setRunes(boolean active, int spreadTicks, Runnable done) {
        World w = world();
        if (w == null || !state().ready()) {
            if (done != null) done.run();
            return;
        }
        ForgeState st = state();
        BlockData data = dataCache.computeIfAbsent(active ? ForgeBlueprint.RUNE_ACTIVE : ForgeBlueprint.RUNE_DORMANT, Bukkit::createBlockData);
        List<int[]> list = runes();
        int n = list.size();
        int ticks = Math.max(1, spreadTicks);
        int per = (int) Math.ceil(n / (double) ticks);
        final int[] cursor = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int end = Math.min(n, cursor[0] + per);
            for (; cursor[0] < end; cursor[0]++) {
                int[] c = list.get(cursor[0]);
                w.getBlockAt(st.centerX + c[0], st.floorY + c[1], st.centerZ + c[2]).setBlockData(data, false);
            }
            if (cursor[0] >= n) {
                task.cancel();
                if (done != null) done.run();
            }
        }, 0L, 1L);
    }
}
