package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.adapter.rollback.structure.block.bridge.HeightmapHandles;
import com.tracel.plugin.adapter.rollback.structure.block.bridge.StateHandles;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.IdentityHashMap;

/**
 * What a server block state is, asked once per distinct state and remembered.
 *
 * <p>States are interned by the server, so identity is the key everywhere.
 */
final class StateTraits {
    static final int AIR = 1;
    static final int ENTITY = 2;
    static final int POI = 4;
    static final int MOTION = 8;
    static final int NO_LEAVES = 16;
    static final int OCEAN = 32;
    static final int SURFACE = 64;

    private final Fuse fuse;

    private final IdentityHashMap<Object, Integer> bits = new IdentityHashMap<>();
    private final IdentityHashMap<Object, BlockData> views = new IdentityHashMap<>();
    private final IdentityHashMap<Object, String> strings = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Material> materials = new IdentityHashMap<>();
    private final IdentityHashMap<BlockData, Object> states = new IdentityHashMap<>();

    StateTraits(Fuse fuse) {
        this.fuse = fuse;
    }

    /**
     * The {@code AIR}.. {@code SURFACE} bits of the state.
     */
    int of(Object state) throws Throwable {
        Integer cached = bits.get(state);
        if (cached != null) return cached;
        int value = 0;
        if ((boolean) StateHandles.IS_AIR.invokeExact(state)) value |= AIR;
        if ((boolean) StateHandles.HAS_BLOCK_ENTITY.invokeExact(state)) value |= ENTITY;
        if ((boolean) StateHandles.HAS_POI.invokeExact(state)) value |= POI;
        if (HeightmapHandles.MOTION_TEST.test(state)) value |= MOTION;
        if (HeightmapHandles.NO_LEAVES_TEST.test(state)) value |= NO_LEAVES;
        if (HeightmapHandles.OCEAN_TEST.test(state)) value |= OCEAN;
        if (HeightmapHandles.SURFACE_TEST.test(state)) value |= SURFACE;
        bits.put(state, value);
        return value;
    }

    /**
     * NMS state of a {@code Bukkit} block data, or {@code null} when it is not a server block data.
     */
    Object stateOf(BlockData data) {
        if (data == null || fuse.dead) return null;
        Object cached = states.get(data);
        if (cached != null) return cached;
        try {
            Object state = StateHandles.CRAFT_STATE.invokeExact((Object) data);
            if (state != null) states.put(data, state);
            return state;
        } catch (Throwable failure) {
            return null;
        }
    }

    boolean isAir(Object state) {
        try {
            return (of(state) & AIR) != 0;
        } catch (Throwable failure) {
            fuse.trip(failure);
            return false;
        }
    }

    boolean hasBlockEntity(Object state) {
        try {
            return (of(state) & ENTITY) != 0;
        } catch (Throwable failure) {
            fuse.trip(failure);
            return true;
        }
    }

    boolean liquid(Object state) {
        try {
            return (boolean) StateHandles.LIQUID.invokeExact(state);
        } catch (Throwable failure) {
            fuse.trip(failure);
            return false;
        }
    }

    boolean canReplace(Object state) {
        try {
            return (boolean) StateHandles.CAN_REPLACE.invokeExact(state);
        } catch (Throwable failure) {
            fuse.trip(failure);
            return false;
        }
    }

    Material material(Object state) {
        Material cached = materials.get(state);
        if (cached != null) return cached;
        try {
            Material value = (Material) StateHandles.MATERIAL.invokeExact(state);
            if (value == null) return Material.AIR;
            materials.put(state, value);
            return value;
        } catch (Throwable failure) {
            fuse.trip(failure);
            return Material.AIR;
        }
    }

    boolean ageable(Object state) {
        return view(state) instanceof Ageable;
    }

    boolean waterlogged(Object state) {
        return view(state) instanceof Waterlogged logged && logged.isWaterlogged();
    }

    String asString(Object state) {
        String cached = strings.get(state);
        if (cached != null) return cached;
        String value = view(state).getAsString();
        strings.put(state, value);
        return value;
    }

    private BlockData view(Object state) {
        BlockData cached = views.get(state);
        if (cached != null) return cached;
        try {
            BlockData created = (BlockData) StateHandles.CREATE_DATA.invokeExact(state);
            views.put(state, created);
            return created;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
