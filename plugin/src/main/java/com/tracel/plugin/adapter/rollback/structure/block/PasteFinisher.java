package com.tracel.plugin.adapter.rollback.structure.block;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * End of a paste: client updates, then heightmaps a chunk at a time, then the relight.
 *
 * <p>Closing all at once held one tick for as long as it took to settle every column of every chunk a big
 * rollback touched. Heightmaps are settled a chunk at a time, so a run always does at least one chunk, however
 * small the budget. The relight is one call into the light engine, which runs the work on its own threads, and
 * goes last.
 */
final class PasteFinisher {
    private static final Logger LOG = Logger.getLogger("Tracel");

    private static final int NOTIFY = 0;
    private static final int HEIGHTMAPS = 1;
    private static final int RELIGHT = 2;
    private static final int DONE = 3;

    private final List<PasteChunk> dirty;
    private final ChangeNotifier notifier;
    private final RelightQueue relight;
    private final HeightmapSettler heightmaps;

    private int phase;
    private int index;

    /**
     * Paste finisher.
     */
    PasteFinisher(List<PasteChunk> dirty, ChangeNotifier notifier, RelightQueue relight, HeightmapSettler heightmaps) {
        this.dirty = dirty;
        this.notifier = notifier;
        this.relight = relight;
        this.heightmaps = heightmaps;
    }

    /**
     * Do what fits in the budget.
     *
     * @return {@code true} when everything is done
     */
    boolean run(long budgetNanos) {
        long start = System.nanoTime();
        if (phase == DONE) {
            if (dirty.isEmpty()) return true;
            phase = NOTIFY;
            index = 0;
        }
        if (phase == NOTIFY) {
            try {
                notifier.flush();
            } catch (Throwable failure) {
                LOG.log(Level.WARNING, "client updates after a rollback paste failed.", failure);
            }
            relight.queue();
            phase = HEIGHTMAPS;
        }
        if (phase == HEIGHTMAPS) {
            try {
                while (index < dirty.size()) {
                    heightmaps.settle(dirty.get(index++));
                    if (index < dirty.size() && System.nanoTime() - start >= budgetNanos) return false;
                }
            } catch (Throwable failure) {
                LOG.log(Level.WARNING, "heightmaps after a rollback paste failed.", failure);
            }
            dirty.clear();
            index = 0;
            phase = RELIGHT;
        }
        relight.run();
        phase = DONE;
        return true;
    }
}
