package com.tracel.plugin.adapter.rollback.structure.block.bridge;

import com.tracel.plugin.specifics.server.ServerClass;
import com.tracel.plugin.specifics.server.ServerMethod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Client notification handles: which players watch a chunk, and telling them a block changed.
 */
public final class NotifyHandles {
    public static final MethodHandle PACK;
    public static final MethodHandle VISIBLE;
    public static final MethodHandle BLOCK_CHANGED;
    public static final MethodHandle HOLDER_CHANGED;
    public static final MethodHandle BEEN_SENT;
    public static final MethodHandle NEW_CURSOR;
    public static final MethodHandle MOVE_CURSOR;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> chunkSource = ServerClass.CHUNK_SOURCE.load();
            Class<?> chunkMap = ServerClass.CHUNK_MAP.load();
            Class<?> chunkPos = ServerClass.CHUNK_POS.load();
            Class<?> holder = ServerClass.CHUNK_HOLDER.load();
            Class<?> blockPos = ServerClass.BLOCK_POS.load();
            Class<?> mutable = ServerClass.MUTABLE_BLOCK_POS.load();

            PACK = Lookups.statik(
                    lookup,
                    chunkPos,
                    ServerMethod.PACK.getId(),
                    MethodType.methodType(
                            long.class,
                            int.class,
                            int.class
                    )
            );
            VISIBLE = Lookups.virtual(
                    lookup,
                    chunkMap,
                    ServerMethod.GET_VISIBLE_CHUNK_IF_PRESENT.getId(),
                    MethodType.methodType(holder, long.class)
            );
            BLOCK_CHANGED = Lookups.virtual(
                    lookup,
                    chunkSource,
                    ServerMethod.BLOCK_CHANGED.getId(),
                    MethodType.methodType(void.class, blockPos)
            );
            HOLDER_CHANGED = Lookups.virtual(
                    lookup,holder,
                    ServerMethod.BLOCK_CHANGED.getId(),
                    MethodType.methodType(boolean.class, blockPos)
            ).asType(MethodType.methodType(
                    void.class,
                    Object.class,
                    Object.class)
            );
            BEEN_SENT = Lookups.virtual(
                    lookup,
                    holder,
                    ServerMethod.MOONRISE_HAS_CHUNK_BEEN_SENT.getId(),
                    MethodType.methodType(boolean.class)
            );
            NEW_CURSOR = Lookups.constructor(lookup, mutable);
            MOVE_CURSOR = Lookups.virtual(
                    lookup,
                    mutable,
                    ServerMethod.SET.getId(),
                    MethodType.methodType(
                            mutable,
                            int.class,
                            int.class,
                            int.class
                    )
            ).asType(MethodType.methodType(void.class, Object.class, int.class, int.class, int.class));
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private NotifyHandles() { }

    static void load() { }
}
