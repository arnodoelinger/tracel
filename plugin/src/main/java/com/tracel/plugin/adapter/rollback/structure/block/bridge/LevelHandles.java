package com.tracel.plugin.adapter.rollback.structure.block.bridge;

import com.tracel.plugin.specifics.server.ServerClass;
import com.tracel.plugin.specifics.server.ServerField;
import com.tracel.plugin.specifics.server.ServerMethod;
import org.bukkit.World;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * World-level server handles: the level behind a {@code Bukkit} world, its chunk source, light engine and
 * capture flags.
 */
public final class LevelHandles {
    public static final Class<?> CRAFT_WORLD;

    public static final MethodHandle GET_HANDLE;
    public static final MethodHandle GET_CHUNK_SOURCE;
    public static final MethodHandle CHUNK_MAP;
    public static final MethodHandle GET_LIGHT;
    public static final MethodHandle NEW_POS;
    public static final MethodHandle UPDATE_POI;

    /**
     * {@code null} on a server without the flag.
     */
    public static final MethodHandle CAPTURE_BLOCKS;

    public static final MethodHandle CAPTURE_TREE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> serverLevel = ServerClass.SERVER_LEVEL.load();
            Class<?> level = ServerClass.LEVEL.load();
            Class<?> chunkSource = ServerClass.CHUNK_SOURCE.load();
            Class<?> chunkMap = ServerClass.CHUNK_MAP.load();
            Class<?> light = ServerClass.LIGHT.load();
            Class<?> blockState = ServerClass.BLOCK_STATE.load();
            Class<?> blockPos = ServerClass.BLOCK_POS.load();

            CRAFT_WORLD = ServerClass.CRAFT_WORLD.load();
            GET_HANDLE = Lookups.virtual(
                    lookup,
                    CRAFT_WORLD,
                    ServerMethod.GET_HANDLE.getId(),
                    MethodType.methodType(serverLevel)
            ).asType(MethodType.methodType(Object.class, World.class));
            GET_CHUNK_SOURCE = Lookups.virtual(
                    lookup,
                    serverLevel,
                    ServerMethod.GET_CHUNK_SOURCE.getId(),
                    MethodType.methodType(chunkSource)
            );
            CHUNK_MAP = Lookups.getter(
                    lookup,
                    chunkSource,
                    ServerField.CHUNK_MAP.getId(),
                    chunkMap
            );
            GET_LIGHT = Lookups.virtual(
                    lookup,
                    chunkSource,
                    ServerMethod.GET_LIGHT_ENGINE.getId(),
                    MethodType.methodType(light)
            );
            NEW_POS = Lookups.constructor(
                    lookup,
                    blockPos,
                    int.class,
                    int.class,
                    int.class
            );
            UPDATE_POI = Lookups.virtual(
                    lookup,
                    serverLevel,
                    ServerMethod.UPDATE_POI_ON_BLOCK_STATE_CHANGE.getId(),
                    MethodType.methodType(
                            void.class,
                            blockPos,
                            blockState,
                            blockState
                    )
            );
            CAPTURE_BLOCKS = Lookups.optionalFlag(
                    lookup,
                    level,
                    ServerField.CAPTURE_BLOCK_STATES.getId()
            );
            CAPTURE_TREE = Lookups.optionalFlag(
                    lookup,
                    level,
                    ServerField.CAPTURE_TREE_GENERATION.getId()
            );
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private LevelHandles() { }

    static void load() { }
}
