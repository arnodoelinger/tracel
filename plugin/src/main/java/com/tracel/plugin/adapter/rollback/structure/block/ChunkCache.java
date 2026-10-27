package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.adapter.rollback.structure.block.bridge.ChunkHandles;

/**
 * Loaded chunks a paste has touched, by chunk coordinates. Blocks of a rollback arrive in runs, so the
 * last chunk is answered before the map is asked.
 */
final class ChunkCache {
    private final Object level;
    private final Object chunkMap;

    private int lastCx = Integer.MIN_VALUE;
    private int lastCz;

    private PasteChunk last;

    private LongMap<PasteChunk> chunks = new LongMap<>();

    /**
     * Chunk cache.
     */
    ChunkCache(Object level, Object chunkMap) {
        this.level = level;
        this.chunkMap = chunkMap;
    }

    /**
     * The chunk, or {@code null} when it is not loaded.
     */
    PasteChunk get(int cx, int cz) throws Throwable {
        if (cx == lastCx && cz == lastCz) return last;
        long key = LongMap.key(cx, cz);
        PasteChunk found = chunks.get(key);
        if (found == null) {
            Object loaded = ChunkHandles.GET_CHUNK.invokeExact(level, cx, cz);
            if (loaded == null) return null;
            found = PasteChunk.open(loaded, chunkMap, cx, cz);
            chunks.put(key, found);
        }
        lastCx = cx;
        lastCz = cz;
        last = found;
        return found;
    }

    /**
     * Forget every chunk. Between ticks one can unload and come back as another object.
     */
    void clear() {
        chunks = new LongMap<>();
        last = null;
        lastCx = Integer.MIN_VALUE;
    }
}
