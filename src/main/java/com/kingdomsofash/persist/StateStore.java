package com.kingdomsofash.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Crash-aware JSON persistence.
 *
 * <ul>
 *   <li>Writes go to a temp file, are fsync'd, the previous good file is kept as {@code .bak}, then the temp
 *       file is atomically moved over the live file.</li>
 *   <li>{@link #saveAsync()} serialises on the caller's (main) thread and hands the string to a single writer
 *       thread; only the newest pending snapshot is written (coalescing).</li>
 *   <li>{@link #saveNow()} is the durable commit used for transactions that must not be lost (material
 *       consumption, claim, ownership transfer): it returns only after the snapshot is on disk.</li>
 *   <li>A corrupt live file falls back to the backup. If BOTH are unreadable the store refuses to load rather
 *       than silently starting empty (which could mint a second legitimate weapon).</li>
 * </ul>
 */
public final class StateStore {
    private final Path file;
    private final Path bak;
    private final Path tmp;
    private final Logger log;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final AtomicReference<String> pending = new AtomicReference<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KingdomsOfAsh-StateWriter");
        t.setDaemon(true);
        return t;
    });
    private final Object writeLock = new Object();
    private volatile Future<?> lastFuture;
    private volatile boolean healthy = true;

    private PersistentState state = new PersistentState();

    public StateStore(Path dir, Logger log) {
        this.file = dir.resolve("state.json");
        this.bak = dir.resolve("state.json.bak");
        this.tmp = dir.resolve("state.json.tmp");
        this.log = log;
    }

    public PersistentState state() {
        return state;
    }

    public boolean healthy() {
        return healthy;
    }

    /** @throws IOException when data exists but cannot be read safely. */
    public void load() throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file) && !Files.exists(bak)) {
            state = new PersistentState().normalise();
            return;
        }
        Exception primary = null;
        if (Files.exists(file)) {
            try {
                state = parse(file);
                return;
            } catch (Exception e) {
                primary = e;
                log.log(Level.SEVERE, "state.json is unreadable, trying backup", e);
            }
        }
        if (Files.exists(bak)) {
            try {
                state = parse(bak);
                Path quarantine = file.resolveSibling("state.json.corrupt-" + Instant.now().toEpochMilli());
                if (Files.exists(file)) Files.move(file, quarantine, StandardCopyOption.REPLACE_EXISTING);
                log.severe("Recovered state from state.json.bak; broken file quarantined as " + quarantine.getFileName());
                saveNow();
                return;
            } catch (Exception e) {
                if (primary != null) e.addSuppressed(primary);
                throw new IOException("Both state.json and state.json.bak are unreadable. Refusing to start with empty "
                        + "state (it could create a second legitimate Oathbreaker). Fix or remove the files deliberately.", e);
            }
        }
        throw new IOException("state.json is unreadable and no backup exists", primary);
    }

    private PersistentState parse(Path p) throws IOException {
        String json = Files.readString(p, StandardCharsets.UTF_8);
        PersistentState s = gson.fromJson(json, PersistentState.class);
        if (s == null) throw new IOException("empty state file " + p);
        return s.normalise();
    }

    public String serialise() {
        return gson.toJson(state);
    }

    public void saveAsync() {
        String json = serialise();
        pending.set(json);
        lastFuture = writer.submit(() -> {
            String s = pending.getAndSet(null);
            if (s != null) write(s);
        });
    }

    /** Durable synchronous commit. Safe to call from the main thread (the file is tiny). */
    public boolean saveNow() {
        String json = serialise();
        pending.set(null);
        try {
            Future<?> f = lastFuture;
            if (f != null) f.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // an in-flight async write only matters for ordering, ignore failures here
        }
        return write(json);
    }

    private boolean write(String json) {
        synchronized (writeLock) {
            try {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    ch.write(ByteBuffer.wrap(bytes));
                    ch.force(true);
                }
                if (Files.exists(file)) Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                healthy = true;
                return true;
            } catch (Exception e) {
                healthy = false;
                log.log(Level.SEVERE, "FAILED to persist state.json", e);
                return false;
            }
        }
    }

    public void close() {
        saveNow();
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
