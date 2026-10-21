package com.kingdomsofash.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Append-only audit trail (plugins/KingdomsOfAsh/audit.log) that is also echoed to the server log. */
public final class Audit {
    private final Path file;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KingdomsOfAsh-Audit");
        t.setDaemon(true);
        return t;
    });
    private final Deque<String> recent = new ArrayDeque<>();

    public Audit(Path dir, Logger log) {
        this.file = dir.resolve("audit.log");
        this.log = log;
    }

    public void log(String category, String message) {
        String line = Instant.now() + " [" + category + "] " + message;
        log.info("[AUDIT] [" + category + "] " + message);
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > 200) recent.removeFirst();
        }
        try {
            io.execute(() -> {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    log.warning("could not append audit log: " + e.getMessage());
                }
            });
        } catch (RuntimeException ignored) {
            // executor already shut down during disable
        }
    }

    public List<String> tail(int n) {
        synchronized (recent) {
            List<String> all = new ArrayList<>(recent);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
