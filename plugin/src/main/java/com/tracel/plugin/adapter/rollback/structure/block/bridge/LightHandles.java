package com.tracel.plugin.adapter.rollback.structure.block.bridge;

import com.tracel.plugin.specifics.server.ServerClass;
import com.tracel.plugin.specifics.server.ServerMethod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

/**
 * Light engine handles.
 */
public final class LightHandles {
    private static final Logger LOG = Logger.getLogger("Tracel");

    public static final MethodHandle DIFFERS;

    /**
     * Bulk relight of chunks; {@code null} on a server without it.
     */
    public static final MethodHandle RELIGHT;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> light = ServerClass.LIGHT.load();
            Class<?> lightEngine = ServerClass.LIGHT_ENGINE.load();
            Class<?> blockState = ServerClass.BLOCK_STATE.load();

            DIFFERS = Lookups.statik(
                    lookup,
                    lightEngine,
                    ServerMethod.HAS_DIFFERENT_LIGHT_PROPERTIES.getId(),
                    MethodType.methodType(boolean.class, blockState, blockState)
            );

            MethodHandle relight = null;
            try {
                relight = Lookups.virtual(
                        lookup,
                        light,
                        ServerMethod.STARLIGHT_SERVER_RELIGHT_CHUNKS.getId(),
                        MethodType.methodType(
                                int.class,
                                Collection.class,
                                Consumer.class,
                                IntConsumer.class
                        )
                ).asType(MethodType.methodType(
                        void.class,
                        Object.class,
                        List.class,
                        Consumer.class,
                        IntConsumer.class)
                );
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
                LOG.warning("section paste has no bulk relight; edited chunks keep their old light until a reload.");
            }
            RELIGHT = relight;
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private LightHandles() { }

    static void load() { }
}
