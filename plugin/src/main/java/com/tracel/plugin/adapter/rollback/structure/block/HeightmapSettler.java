package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.adapter.rollback.structure.block.bridge.HeightmapHandles;

import java.util.List;

/**
 * Brings the four surface heightmaps of a chunk up to date once a paste is done with it, instead of after every write.
 */
final class HeightmapSettler {
    private static final int NONE = Integer.MIN_VALUE;

    private final StateTraits traits;
    private final List<PasteChunk> dirty;

    /**
     * Heightmap settler.
     */
    HeightmapSettler(StateTraits traits, List<PasteChunk> dirty) {
        this.traits = traits;
        this.dirty = dirty;
    }

    /**
     * A block of the column was written at {@code y}. The first column of a chunk puts the chunk on the dirty list.
     */
    void note(PasteChunk chunk, int localX, int y, int localZ) {
        int column = (localZ << 4) | localX;
        chunk.columns[column >> 6] |= 1L << (column & 63);
        if (y <= chunk.columnTop[column]) return;
        chunk.columnTop[column] = y;
        if (chunk.listed) return;
        chunk.listed = true;
        dirty.add(chunk);
    }

    /**
     * Settles heightmap.
     */
    void settle(PasteChunk chunk) throws Throwable {
        for (int column = 0; column < 256; column++) {
            if ((chunk.columns[column >> 6] & (1L << (column & 63))) == 0) continue;
            settleColumn(chunk, column & 15, column >> 4, chunk.columnTop[column]);
        }
    }

    private void settleColumn(PasteChunk chunk, int localX, int localZ, int top) throws Throwable {
        int lowest = chunk.minSection << 4;
        int motionAt = firstAvailable(chunk.motion, localX, localZ);
        int leavesAt = firstAvailable(chunk.motionNoLeaves, localX, localZ);
        int oceanAt = firstAvailable(chunk.ocean, localX, localZ);
        int surfaceAt = firstAvailable(chunk.surface, localX, localZ);
        boolean motion = motionAt != NONE;
        boolean leaves = leavesAt != NONE;
        boolean ocean = oceanAt != NONE;
        boolean surface = surfaceAt != NONE;
        for (int y = top; y >= lowest && (motion || leaves || ocean || surface); y--) {
            boolean reached = (motion && y >= motionAt - 1) || (leaves && y >= leavesAt - 1)
                    || (ocean && y >= oceanAt - 1) || (surface && y >= surfaceAt - 1);
            if (!reached) return;
            Object state = chunk.stateAt(localX, y, localZ);
            if (state == null) return;
            int bits = traits.of(state);
            if (settles(motion, y, motionAt, bits, StateTraits.MOTION)) {
                HeightmapHandles.LIFT.invokeExact(chunk.motion, localX, y, localZ, state);
                motion = false;
            }
            if (settles(leaves, y, leavesAt, bits, StateTraits.NO_LEAVES)) {
                HeightmapHandles.LIFT.invokeExact(chunk.motionNoLeaves, localX, y, localZ, state);
                leaves = false;
            }
            if (settles(ocean, y, oceanAt, bits, StateTraits.OCEAN)) {
                HeightmapHandles.LIFT.invokeExact(chunk.ocean, localX, y, localZ, state);
                ocean = false;
            }
            if (settles(surface, y, surfaceAt, bits, StateTraits.SURFACE)) {
                HeightmapHandles.LIFT.invokeExact(chunk.surface, localX, y, localZ, state);
                surface = false;
            }
        }
    }

    // The map is done at the first solid block at or under its old surface, or one under it when there is none
    private static boolean settles(boolean open, int y, int at, int bits, int flag) {
        return open && y >= at - 1 && ((bits & flag) != 0 || y == at - 1);
    }

    private static int firstAvailable(Object heightmap, int localX, int localZ) throws Throwable {
        if (heightmap == null) return NONE;
        return (int) HeightmapHandles.FIRST_AVAILABLE.invokeExact(heightmap, localX, localZ);
    }
}
