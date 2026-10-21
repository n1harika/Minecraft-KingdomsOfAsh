package com.kingdomsofash;

import com.kingdomsofash.persist.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    @TempDir
    Path dir;

    @Test
    void roundTripAndBackupFallback() throws Exception {
        Logger log = Logger.getLogger("test");
        StateStore s = new StateStore(dir, log);
        s.load();
        s.state().weapon.beginReward(UUID.randomUUID(), 5);
        assertTrue(s.saveNow());
        s.state().ritual.attempts = 3;
        assertTrue(s.saveNow()); // creates .bak of the first
        s.close();

        StateStore again = new StateStore(dir, log);
        again.load();
        assertEquals(3, again.state().ritual.attempts);
        assertEquals("REWARD_PENDING", again.state().weapon.phase.name());

        Files.writeString(dir.resolve("state.json"), "{ not json");
        StateStore recovered = new StateStore(dir, log);
        recovered.load();
        assertEquals("REWARD_PENDING", recovered.state().weapon.phase.name(), "fell back to backup");
        recovered.close();
    }

    @Test
    void refusesToStartEmptyWhenEverythingIsCorrupt() throws Exception {
        Files.writeString(dir.resolve("state.json"), "garbage");
        Files.writeString(dir.resolve("state.json.bak"), "also garbage");
        StateStore s = new StateStore(dir, Logger.getLogger("test"));
        assertThrows(IOException.class, s::load);
    }
}
