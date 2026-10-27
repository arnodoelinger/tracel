package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.adapter.rollback.structure.block.bridge.ChunkHandles;
import com.tracel.plugin.adapter.rollback.structure.block.bridge.LightHandles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Chunks whose light a paste may have changed, relit in one call at the end.
 *
 * <p>A write near a chunk edge can change the light of the neighbour as well. Each chunk keeps a bit per
 * neighbour that is within light range of something it changed.
 */
final class RelightQueue {
    private static final Logger LOG = Logger.getLogger("Tracel");

    private static final int ALL_EDGES = 0xff;

    // (DX[i], DZ[i]): east, west, south, north, then the corners
    private static final int[] DX = {1, -1, 0, 0, 1, -1, 1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, 1, -1, -1};

    private final Object level;
    private final Object light;

    private final List<PasteChunk> dirty;

    private final LongMap<Object> seen = new LongMap<>();
    private final List<Object> positions = new ArrayList<>();

    /**
     * Relight queue.
     */
    RelightQueue(Object level, Object light, List<PasteChunk> dirty) {
        this.level = level;
        this.light = light;
        this.dirty = dirty;
    }

    /**
     * Mark the neighbours a write at the cell can leave with stale light.
     */
    void note(PasteChunk chunk, int localX, int localZ, Object before, int beforeTraits, Object next, int nextTraits)
            throws Throwable {
        if (chunk.lightEdges == ALL_EDGES) return;
        boolean affects = (beforeTraits & StateTraits.AIR) != (nextTraits & StateTraits.AIR);
        if (!affects) affects = (boolean) LightHandles.DIFFERS.invokeExact(before, next);
        if (affects) chunk.lightEdges |= reach(localX, localZ);
    }

    /**
     * Turn the edge bits of every dirty chunk into the chunks to relight.
     */
    void queue() {
        for (PasteChunk chunk : dirty) {
            int edges = chunk.lightEdges;
            if (edges == 0) continue;
            mark(chunk.nms, chunk.cx, chunk.cz);
            for (int i = 0; i < DX.length; i++) {
                if ((edges & (1 << i)) != 0) mark(null, chunk.cx + DX[i], chunk.cz + DZ[i]);
            }
        }
    }

    /**
     * Hand the queued chunks to the light engine.
     */
    void run() {
        if (positions.isEmpty() || LightHandles.RELIGHT == null) return;
        try {
            LightHandles.RELIGHT.invokeExact(light, positions, (Consumer<Object>) null, (IntConsumer) null);
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "chunk relight after a rollback paste failed.", failure);
        } finally {
            positions.clear();
        }
    }

    private static int reach(int localX, int localZ) {
        int edges = 0;
        if (localX >= 1) edges |= 1;
        if (localX <= 14) edges |= 2;
        if (localZ >= 1) edges |= 4;
        if (localZ <= 14) edges |= 8;
        if ((16 - localX) + (16 - localZ) <= 15) edges |= 16;
        if ((localX + 1) + (16 - localZ) <= 15) edges |= 32;
        if ((16 - localX) + (localZ + 1) <= 15) edges |= 64;
        if ((localX + 1) + (localZ + 1) <= 15) edges |= 128;
        return edges;
    }

    private void mark(Object known, int cx, int cz) {
        long key = LongMap.key(cx, cz);
        if (seen.get(key) != null) return;
        try {
            Object chunk = known != null ? known : ChunkHandles.GET_CHUNK.invokeExact(level, cx, cz);
            if (chunk == null) return;
            Object pos = ChunkHandles.GET_POS.invokeExact(chunk);
            seen.put(key, pos);
            positions.add(pos);
        } catch (Throwable failure) {
            LOG.log(Level.FINE, "a neighbor chunk was left out of the relight.", failure);
        }
    }
}
