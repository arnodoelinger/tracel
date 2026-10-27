package com.tracel.plugin.adapter.rollback.structure.block.bridge;

import com.tracel.plugin.specifics.server.ServerClass;
import com.tracel.plugin.specifics.server.ServerMethod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Predicate;

/**
 * Chunk and chunk section handles: lookup, raw state reads and writes.
 */
public final class ChunkHandles {
    public static final MethodHandle GET_CHUNK;
    public static final MethodHandle GET_POS;
    public static final MethodHandle GET_SECTIONS;
    public static final MethodHandle GET_MIN_Y;
    public static final MethodHandle MARK_UNSAVED;
    public static final MethodHandle GET_STATE;
    public static final MethodHandle SET_STATE;

    /**
     * {@code null} on a server without the section probe.
     */
    public static final MethodHandle MAYBE_HAS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> serverLevel = ServerClass.SERVER_LEVEL.load();
            Class<?> chunkAccess = ServerClass.CHUNK_ACCESS.load();
            Class<?> levelChunk = ServerClass.LEVEL_CHUNK.load();
            Class<?> section = ServerClass.SECTION.load();
            Class<?> blockState = ServerClass.BLOCK_STATE.load();
            Class<?> chunkPos = ServerClass.CHUNK_POS.load();

            GET_CHUNK = Lookups.virtual(
                    lookup,
                    serverLevel,
                    ServerMethod.GET_CHUNK_IF_LOADED.getId(),
                    MethodType.methodType(levelChunk, int.class, int.class)
            );
            GET_POS = Lookups.virtual(
                    lookup,
                    chunkAccess,
                    ServerMethod.GET_POS.getId(),
                    MethodType.methodType(chunkPos));
            GET_SECTIONS = Lookups.virtual(
                    lookup,
                    chunkAccess,
                    ServerMethod.GET_SECTIONS.getId(),
                    MethodType.methodType(section.arrayType()));
            GET_MIN_Y = Lookups.virtual(
                    lookup,
                    chunkAccess,
                    ServerMethod.GET_MIN_Y.getId(),
                    MethodType.methodType(int.class));
            MARK_UNSAVED = Lookups.virtual(
                    lookup,
                    levelChunk,
                    ServerMethod.MARK_UNSAVED.getId(),
                    MethodType.methodType(void.class));
            GET_STATE = Lookups.virtual(
                    lookup,
                    section,
                    ServerMethod.GET_BLOCK_STATE.getId(),
                    MethodType.methodType(blockState, int.class, int.class, int.class));
            SET_STATE = Lookups.virtual(
                    lookup,
                    section,
                    ServerMethod.SET_BLOCK_STATE.getId(),
                    MethodType.methodType(blockState, int.class, int.class, int.class, blockState, boolean.class));

            MethodHandle maybeHas = null;
            try {
                maybeHas = Lookups.virtual(
                        lookup,
                        section,
                        ServerMethod.MAYBE_HAS.getId(),
                        MethodType.methodType(boolean.class, Predicate.class));
            } catch (NoSuchMethodException | IllegalAccessException ignored) { }

            MAYBE_HAS = maybeHas;
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private ChunkHandles() { }

    static void load() { }
}
