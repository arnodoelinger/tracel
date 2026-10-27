package com.tracel.plugin.adapter.rollback.structure.block.bridge;

import com.tracel.plugin.specifics.server.HeightmapType;
import com.tracel.plugin.specifics.server.ServerClass;
import com.tracel.plugin.specifics.server.ServerField;
import com.tracel.plugin.specifics.server.ServerMethod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Heightmap handles: the four surface maps a chunk keeps, and what counts as their surface.
 */
public final class HeightmapHandles {
    public static final MethodHandle HEIGHTMAPS;
    public static final MethodHandle LIFT;
    public static final MethodHandle FIRST_AVAILABLE;

    public static final Object MOTION;
    public static final Object MOTION_NO_LEAVES;
    public static final Object OCEAN;
    public static final Object SURFACE;

    public static final Predicate<Object> MOTION_TEST;
    public static final Predicate<Object> NO_LEAVES_TEST;
    public static final Predicate<Object> OCEAN_TEST;
    public static final Predicate<Object> SURFACE_TEST;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> chunkAccess = ServerClass.CHUNK_ACCESS.load();
            Class<?> blockState = ServerClass.BLOCK_STATE.load();
            Class<?> heightmap = ServerClass.HEIGHTMAP.load();
            Class<?> types = ServerClass.HEIGHTMAP_TYPES.load();

            HEIGHTMAPS = Lookups.getter(
                    lookup,
                    chunkAccess,
                    ServerField.HEIGHTMAPS.getId(),
                    Map.class
            ).asType(MethodType.methodType(Map.class, Object.class));
            LIFT = Lookups.virtual(
                    lookup,
                    heightmap,
                    ServerMethod.UPDATE.getId(),
                    MethodType.methodType(
                            boolean.class,
                            int.class,
                            int.class,
                            int.class,
                            blockState
                    )
            ).asType(MethodType.methodType(
                    void.class,
                    Object.class,
                    int.class,
                    int.class,
                    int.class,
                    Object.class)
            );
            FIRST_AVAILABLE = Lookups.virtual(
                    lookup,
                    heightmap,
                    ServerMethod.GET_FIRST_AVAILABLE.getId(),
                    MethodType.methodType(
                            int.class,
                            int.class,
                            int.class
                    )
            );

            MethodHandle opaque = Lookups.virtual(
                    lookup,
                    types,
                    ServerMethod.IS_OPAQUE.getId(),
                    MethodType.methodType(Predicate.class)
            );

            MOTION = Lookups.enumConst(
                    types,
                    HeightmapType.MOTION_BLOCKING.name()
            );
            MOTION_NO_LEAVES = Lookups.enumConst(
                    types,
                    HeightmapType.MOTION_BLOCKING_NO_LEAVES.name()
            );
            OCEAN = Lookups.enumConst(
                    types,
                    HeightmapType.OCEAN_FLOOR.name()
            );
            SURFACE = Lookups.enumConst(
                    types,
                    HeightmapType.WORLD_SURFACE.name()
            );

            MOTION_TEST = test(opaque, MOTION);
            NO_LEAVES_TEST = test(opaque, MOTION_NO_LEAVES);
            OCEAN_TEST = test(opaque, OCEAN);
            SURFACE_TEST = test(opaque, SURFACE);
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private HeightmapHandles() { }

    static void load() { }

    @SuppressWarnings("unchecked")
    private static Predicate<Object> test(MethodHandle opaque, Object type) throws Throwable {
        return (Predicate<Object>) (Predicate<?>) opaque.invoke(type);
    }
}
