package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.adapter.rollback.structure.block.bridge.ChunkHandles;
import com.tracel.plugin.adapter.rollback.structure.block.bridge.HeightmapHandles;
import com.tracel.plugin.adapter.rollback.structure.block.bridge.NotifyHandles;

import java.util.Arrays;
import java.util.Map;

/**
 * One loaded chunk touched by a paste: sections, heightmaps, and the deferred client queue.
 */
final class PasteChunk {
    final Object nms;
    final Object[] sections;

    final int minSection;
    final int cx;
    final int cz;

    final Object holder;
    final Object motion;
    final Object motionNoLeaves;
    final Object ocean;
    final Object surface;

    final long[] columns = new long[4];
    final int[] columnTop = new int[256];

    boolean listed;
    boolean unsaved;
    boolean armed;

    int lightEdges;
    int[] changes;
    int changeCount;
    byte watch;
    int cachedIndex = Integer.MIN_VALUE;

    Object cachedSection;

    private PasteChunk(
            Object nms,
            Object[] sections,
            int minSection,
            int cx,
            int cz,
            Object holder,
            Object motion,
            Object motionNoLeaves,
            Object ocean,
            Object surface
    ) {
        this.nms = nms;
        this.sections = sections;
        this.minSection = minSection;
        this.cx = cx;
        this.cz = cz;
        this.holder = holder;
        this.motion = motion;
        this.motionNoLeaves = motionNoLeaves;
        this.ocean = ocean;
        this.surface = surface;
        Arrays.fill(columnTop, Integer.MIN_VALUE);
    }

    @SuppressWarnings("unchecked")
    static PasteChunk open(Object chunk, Object chunkMap, int cx, int cz) throws Throwable {
        Object sections = ChunkHandles.GET_SECTIONS.invokeExact(chunk);
        int minY = (int) ChunkHandles.GET_MIN_Y.invokeExact(chunk);
        Map<Object, Object> maps = (Map<Object, Object>) HeightmapHandles.HEIGHTMAPS.invokeExact(chunk);
        long packed = (long) NotifyHandles.PACK.invokeExact(cx, cz);
        Object holder = NotifyHandles.VISIBLE.invokeExact(chunkMap, packed);
        return new PasteChunk(
                chunk,
                (Object[]) sections,
                minY >> 4,
                cx,
                cz,
                holder,
                maps.get(HeightmapHandles.MOTION),
                maps.get(HeightmapHandles.MOTION_NO_LEAVES),
                maps.get(HeightmapHandles.OCEAN),
                maps.get(HeightmapHandles.SURFACE)
        );
    }

    /**
     * Section at block y, or {@code null} when the height is outside this chunk.
     */
    Object section(int y) {
        int index = PalettePaste.sectionIndex(y, minSection);
        if (index == cachedIndex) return cachedSection;
        cachedIndex = index;
        if (index < 0 || index >= sections.length) {
            cachedSection = null;
            return null;
        }
        cachedSection = sections[index];
        return cachedSection;
    }

    Object stateAt(int localX, int y, int localZ) throws Throwable {
        int index = PalettePaste.sectionIndex(y, minSection);
        if (index < 0 || index >= sections.length) return null;
        Object section = sections[index];
        if (section == null) return null;
        return ChunkHandles.GET_STATE.invokeExact(section, localX, y & 15, localZ);
    }
}
