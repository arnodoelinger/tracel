package com.tracel.plugin.adapter.rollback.structure.block.bridge;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether the server classes behind the section paste are the ones we know.
 *
 * <p>The handles live in the groups next to this class, as {@code static final} fields so the JIT can see
 * through them. A group resolves itself the first time it is touched; {@link #ready()} touches all of them once.
 * Any group failing turns the paste off for the process, and rollbacks write through {@code Bukkit}.
 */
public final class PasteBridge {
    private static final Logger LOG = Logger.getLogger("Tracel");

    private static volatile int state; // 0 unknown, 1 ready, -1 unavailable

    private PasteBridge() { }

    public static boolean ready() {
        int known = state;
        if (known != 0) return known > 0;
        synchronized (PasteBridge.class) {
            if (state != 0) return state > 0;
            try {
                LevelHandles.load();
                ChunkHandles.load();
                StateHandles.load();
                HeightmapHandles.load();
                NotifyHandles.load();
                LightHandles.load();
                state = 1;
            } catch (Throwable failure) {
                fail(failure);
            }
            return state > 0;
        }
    }

    public static void fail(Throwable failure) {
        if (state < 0) return;
        state = -1;
        LOG.log(Level.WARNING, "direct section paste is unavailable; rollbacks will write blocks through bukkit.", failure);
    }
}
