package com.tracel.plugin.adapter.rollback.structure.block;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared kill switch of one paste. Once tripped, every write is refused and callers fall back to {@code Bukkit}.
 */
final class Fuse {
    private static final Logger LOG = Logger.getLogger("Tracel");

    boolean dead;

    void trip(Throwable failure) {
        if (dead) return;
        dead = true;
        LOG.log(Level.WARNING, "section paste failed mid-write; the rest of this group uses bukkit.", failure);
    }
}
