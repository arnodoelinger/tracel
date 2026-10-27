package com.tracel.plugin.adapter.rollback.structure.block.bridge;

import com.tracel.plugin.specifics.server.ServerClass;
import com.tracel.plugin.specifics.server.ServerMethod;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Block state handles: questions about a state and the bridge to {@code Bukkit} block data.
 */
public final class StateHandles {
    public static final MethodHandle IS_AIR;
    public static final MethodHandle HAS_BLOCK_ENTITY;
    public static final MethodHandle HAS_POI;
    public static final MethodHandle LIQUID;
    public static final MethodHandle CAN_REPLACE;
    public static final MethodHandle MATERIAL;
    public static final MethodHandle CRAFT_STATE;
    public static final MethodHandle CREATE_DATA;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> blockState = ServerClass.BLOCK_STATE.load();
            Class<?> stateBase = ServerClass.STATE_BASE.load();
            Class<?> craftData = ServerClass.CRAFT_DATA.load();
            Class<?> poiTypes = ServerClass.POI_TYPES.load();

            MethodType question = MethodType.methodType(boolean.class);
            IS_AIR = Lookups.virtual(
                    lookup,
                    stateBase,
                    ServerMethod.IS_AIR.getId(),
                    question
            );
            HAS_BLOCK_ENTITY = Lookups.virtual(
                    lookup,
                    stateBase,
                    ServerMethod.HAS_BLOCK_ENTITY.getId(),
                    question
            );
            HAS_POI = Lookups.statik(
                    lookup,
                    poiTypes,
                    ServerMethod.HAS_POI.getId(),
                    MethodType.methodType(boolean.class, blockState)
            );
            LIQUID = Lookups.virtual(
                    lookup,
                    stateBase,
                    ServerMethod.LIQUID.getId(),
                    question
            );
            CAN_REPLACE = Lookups.virtual(
                    lookup,
                    stateBase,
                    ServerMethod.CAN_BE_REPLACED.getId(),
                    question
            );
            MATERIAL = Lookups.virtual(
                    lookup,
                    blockState,
                    ServerMethod.GET_BUKKIT_MATERIAL.getId(),
                    MethodType.methodType(Material.class)
            ).asType(MethodType.methodType(Material.class, Object.class));
            CRAFT_STATE = Lookups.virtual(
                    lookup,
                    craftData,
                    ServerMethod.GET_STATE.getId(),
                    MethodType.methodType(blockState)
            );
            CREATE_DATA = Lookups.statik(
                    lookup,
                    craftData,
                    ServerMethod.CREATE_DATA.getId(),
                    MethodType.methodType(craftData, blockState)
            ).asType(MethodType.methodType(BlockData.class, Object.class));
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private StateHandles() { }

    static void load() { }
}
