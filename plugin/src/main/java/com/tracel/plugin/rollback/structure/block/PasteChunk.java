package com.tracel.plugin.rollback.structure.block;

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
    static PasteChunk open(PasteBridge nms, Object chunk, Object chunkMap, int cx, int cz) throws Throwable {
        Object sections = nms.getSections.invokeExact(chunk);
        int minY = (int) nms.getMinY.invokeExact(chunk);
        Map<Object, Object> maps = (Map<Object, Object>) nms.heightmaps.invokeExact(chunk);
        long packed = (long) nms.pack.invokeExact(cx, cz);
        Object holder = nms.visible.invokeExact(chunkMap, packed);
        return new PasteChunk(
                chunk,
                (Object[]) sections,
                minY >> 4,
                cx,
                cz,
                holder,
                maps.get(nms.motion),
                maps.get(nms.motionNoLeaves),
                maps.get(nms.ocean),
                maps.get(nms.surface)
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

    Object stateAt(PasteBridge nms, int localX, int y, int localZ) throws Throwable {
        int index = PalettePaste.sectionIndex(y, minSection);
        if (index < 0 || index >= sections.length) return null;
        Object section = sections[index];
        if (section == null) return null;
        return nms.getState.invokeExact(section, localX, y & 15, localZ);
    }
}
