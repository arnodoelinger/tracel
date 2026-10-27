package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.adapter.rollback.structure.block.bridge.ChunkHandles;
import org.bukkit.Material;

import java.util.IdentityHashMap;
import java.util.function.Predicate;

/**
 * Answers "certainly no fire here" from the section palette, without reading a single block.
 */
final class FireProbe {
    private final Fuse fuse;
    private final ChunkCache chunks;
    private final IdentityHashMap<Object, Boolean> free = new IdentityHashMap<>();
    private final Predicate<Object> fire;

    private int cx = Integer.MIN_VALUE;
    private int cz;
    private int section;

    private boolean top;
    private boolean answer;

    FireProbe(Fuse fuse, ChunkCache chunks, StateTraits traits) {
        this.fuse = fuse;
        this.chunks = chunks;
        this.fire = state -> {
            Material material = traits.material(state);
            return material == Material.FIRE || material == Material.SOUL_FIRE;
        };
    }

    /**
     * Whether the section holding the block certainly has no fire in it.
     */
    boolean freeAt(int x, int y, int z) {
        if (fuse.dead || ChunkHandles.MAYBE_HAS == null) return false;
        try {
            PasteChunk chunk = chunks.get(x >> 4, z >> 4);
            if (chunk == null) return false;
            Object section = chunk.section(y);
            if (section == null) return false;
            Boolean known = free.get(section);
            if (known != null) return known;
            boolean clear = !(boolean) ChunkHandles.MAYBE_HAS.invokeExact(section, (Object) fire);
            free.put(section, clear);
            return clear;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * {@link #freeAt} for the block and the one above it, answered once per section: blocks come in the order they
     * sit in, so a run of them shares the answer of the first.
     */
    boolean freeAround(int x, int y, int z) {
        int nextCx = x >> 4;
        int nextCz = z >> 4;
        int nextSection = y >> 4;
        boolean nextTop = (y & 15) == 15;
        if (nextCx == cx && nextCz == cz && nextSection == section && nextTop == top) return answer;
        boolean next = freeAt(x, y, z) && freeAt(x, y + 1, z);
        cx = nextCx;
        cz = nextCz;
        section = nextSection;
        top = nextTop;
        answer = next;
        return next;
    }

    /**
     * Sections are not the same objects after a chunk comes back.
     */
    void reset() {
        free.clear();
        cx = Integer.MIN_VALUE;
    }
}
