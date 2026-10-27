package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.adapter.rollback.structure.block.bridge.NotifyHandles;

import java.util.Arrays;
import java.util.List;

/**
 * Tells the clients watching a chunk which of its blocks a paste changed, in one go instead of per write.
 */
final class ChangeNotifier {
    private final Object chunkSource;
    private final Object cursor;
    private final List<PasteChunk> dirty;

    ChangeNotifier(Object chunkSource, Object cursor, List<PasteChunk> dirty) {
        this.chunkSource = chunkSource;
        this.cursor = cursor;
        this.dirty = dirty;
    }

    /**
     * Remember that the block changed, unless nobody was sent this chunk yet.
     */
    void queue(PasteChunk chunk, int localX, int localZ, int y) throws Throwable {
        if (chunk.holder == null) return;
        if (chunk.watch == 0) {
            chunk.watch = (boolean) NotifyHandles.BEEN_SENT.invokeExact(chunk.holder) ? (byte) 1 : (byte) -1;
        }
        if (chunk.watch < 0) return;
        int count = chunk.changeCount;
        int[] changes = chunk.changes;
        if (changes == null) {
            changes = chunk.changes = new int[64];
        } else if (count == changes.length) {
            changes = chunk.changes = Arrays.copyOf(changes, count << 1);
        }
        changes[count] = (y << 8) | (localZ << 4) | localX;
        chunk.changeCount = count + 1;
    }

    /**
     * Send what is queued. The queue is emptied even when sending fails; a half-sent one would only go out twice.
     */
    void flush() throws Throwable {
        try {
            for (PasteChunk chunk : dirty) send(chunk);
        } finally {
            for (PasteChunk chunk : dirty) chunk.changeCount = 0;
        }
    }

    private void send(PasteChunk chunk) throws Throwable {
        int count = chunk.changeCount;
        if (count == 0) return;
        int baseX = chunk.cx << 4;
        int baseZ = chunk.cz << 4;
        int[] changes = chunk.changes;
        for (int i = 0; i < count; i++) {
            int packed = changes[i];
            int x = baseX | (packed & 15);
            int z = baseZ | ((packed >> 4) & 15);
            int y = packed >> 8;
            NotifyHandles.MOVE_CURSOR.invokeExact(cursor, x, y, z);
            if (!chunk.armed) {
                NotifyHandles.BLOCK_CHANGED.invokeExact(chunkSource, cursor);
                chunk.armed = true;
            } else {
                NotifyHandles.HOLDER_CHANGED.invokeExact(chunk.holder, cursor);
            }
        }
    }
}
