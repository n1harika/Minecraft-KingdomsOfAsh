package com.kingdomsofash.ore;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.util.Keys;
import com.kingdomsofash.util.Signer;
import com.kingdomsofash.util.Text;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;

/**
 * Bloodiron identity. Two independent anchors:
 * <ul>
 *   <li><b>Blocks</b>: the generator records every Bloodiron block position in the chunk's persistent data, so a
 *       block's identity does not depend on its appearance (the host block type is just a vanilla block).</li>
 *   <li><b>Items</b>: only items carrying our HMAC-signed tag count; renamed ordinary blocks, creative copies of
 *       plain nether-gold ore and hand-forged NBT are all rejected.</li>
 * </ul>
 */
public final class BloodironService {
    private static final String PAYLOAD = "bloodiron-ore-v1";

    private final Plugin plugin;
    private final Settings s;
    private final Signer signer;

    public BloodironService(Plugin plugin, Settings s, Signer signer) {
        this.plugin = plugin;
        this.s = s;
        this.signer = signer;
    }

    // ------------------------------------------------------------------ items

    public ItemStack create(int amount) {
        ItemStack it = new ItemStack(s.bloodironHost, Math.max(1, Math.min(amount, 64)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item("<gradient:#d02030:#6e0a12><bold>Bloodiron Ore</bold></gradient>"));
        m.lore(List.of(
                Text.item("<dark_gray>Iron that remembers the oath."),
                Text.item(""),
                Text.item("<gray>Gathered only with <red>Silk Touch<gray>."),
                Text.item("<gray>" + s.bloodironRequired + " of these open the <dark_red>Lost Forge<gray>."),
                Text.item("<dark_gray>It cannot be placed or smelted.")));
        m.setEnchantmentGlintOverride(true);
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(Keys.ORE_ITEM, PersistentDataType.BYTE, (byte) 1);
        pdc.set(Keys.ORE_SIG, PersistentDataType.STRING, signer.sign(PAYLOAD));
        it.setItemMeta(m);
        return it;
    }

    /** Claims to be Bloodiron (tag present), valid or not. */
    public boolean claims(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getPersistentDataContainer().has(Keys.ORE_ITEM, PersistentDataType.BYTE);
    }

    public boolean isAuthentic(ItemStack it) {
        if (it == null || it.getType() != s.bloodironHost || !it.hasItemMeta()) return false;
        var pdc = it.getPersistentDataContainer();
        Byte b = pdc.get(Keys.ORE_ITEM, PersistentDataType.BYTE);
        return b != null && b == 1 && signer.verify(PAYLOAD, pdc.get(Keys.ORE_SIG, PersistentDataType.STRING));
    }

    public int countAuthentic(Player p) {
        int n = 0;
        for (ItemStack st : p.getInventory().getStorageContents()) {
            if (isAuthentic(st)) n += st.getAmount();
        }
        return n;
    }

    /** Removes exactly {@code amount} authentic ore. Returns false (and removes nothing) if not enough. */
    public boolean take(Player p, int amount) {
        if (countAuthentic(p) < amount) return false;
        PlayerInventory inv = p.getInventory();
        int left = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack st = contents[i];
            if (!isAuthentic(st)) continue;
            int take = Math.min(left, st.getAmount());
            left -= take;
            if (take == st.getAmount()) inv.setItem(i, null);
            else {
                st.setAmount(st.getAmount() - take);
                inv.setItem(i, st);
            }
        }
        return left == 0;
    }

    /** Puts back what {@link #take} removed (used when the durable commit of an accepted attempt fails). */
    public void giveBack(Player p, int amount) {
        int left = amount;
        while (left > 0) {
            ItemStack st = create(Math.min(64, left));
            left -= st.getAmount();
            var over = p.getInventory().addItem(st);
            for (ItemStack o : over.values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
        }
    }

    // ------------------------------------------------------------------ block tracking (chunk PDC)

    private static int pack(int x, int y, int z) {
        return ((y + 64) << 8) | ((x & 15) << 4) | (z & 15);
    }

    private static int[] read(Chunk c) {
        long[] arr = c.getPersistentDataContainer().get(Keys.ORE_CHUNK, PersistentDataType.LONG_ARRAY);
        if (arr == null) return new int[0];
        int[] out = new int[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = (int) arr[i];
        return out;
    }

    private static void write(Chunk c, int[] data) {
        if (data.length == 0) {
            c.getPersistentDataContainer().remove(Keys.ORE_CHUNK);
            return;
        }
        long[] arr = new long[data.length];
        for (int i = 0; i < data.length; i++) arr[i] = data[i];
        c.getPersistentDataContainer().set(Keys.ORE_CHUNK, PersistentDataType.LONG_ARRAY, arr);
    }

    public boolean isTracked(Block b) {
        if (b.getType() != s.bloodironHost) return false;
        int key = pack(b.getX(), b.getY(), b.getZ());
        for (int v : read(b.getChunk())) if (v == key) return true;
        return false;
    }

    public void track(Block b) {
        Chunk c = b.getChunk();
        int key = pack(b.getX(), b.getY(), b.getZ());
        int[] cur = read(c);
        for (int v : cur) if (v == key) return;
        int[] n = Arrays.copyOf(cur, cur.length + 1);
        n[cur.length] = key;
        write(c, n);
    }

    /** Track many positions of one chunk in a single read/write. */
    public void trackAll(Chunk c, int[] packed, int count) {
        int[] cur = read(c);
        int[] n = Arrays.copyOf(cur, cur.length + count);
        System.arraycopy(packed, 0, n, cur.length, count);
        write(c, n);
    }

    public static int packLocal(int x, int y, int z) {
        return pack(x, y, z);
    }

    public boolean untrack(Block b) {
        Chunk c = b.getChunk();
        int key = pack(b.getX(), b.getY(), b.getZ());
        int[] cur = read(c);
        int idx = -1;
        for (int i = 0; i < cur.length; i++) if (cur[i] == key) idx = i;
        if (idx < 0) return false;
        int[] n = new int[cur.length - 1];
        System.arraycopy(cur, 0, n, 0, idx);
        System.arraycopy(cur, idx + 1, n, idx, cur.length - idx - 1);
        write(c, n);
        return true;
    }

    public int trackedInChunk(Chunk c) {
        return read(c).length;
    }

    public Plugin plugin() {
        return plugin;
    }
}
