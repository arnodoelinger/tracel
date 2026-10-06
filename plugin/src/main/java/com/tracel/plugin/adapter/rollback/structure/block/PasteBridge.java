package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.specifics.server.HeightmapType;
import com.tracel.plugin.specifics.server.ServerClass;
import com.tracel.plugin.specifics.server.ServerField;
import com.tracel.plugin.specifics.server.ServerMethod;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server methods used to read and write a chunk section.
 */
final class PasteBridge {
    private static final Logger LOG = Logger.getLogger("Tracel");
    private static volatile PasteBridge bridge;
    private static volatile boolean unavailable;

    final Class<?> craftWorld;
    final MethodHandle getHandle;
    final MethodHandle getChunkSource;
    final MethodHandle chunkMap;
    final MethodHandle getLight;
    final MethodHandle getChunk;
    final MethodHandle getSections;
    final MethodHandle getMinY;
    final MethodHandle getState;
    final MethodHandle setState;
    final MethodHandle onlyAir;
    final MethodHandle isAir;
    final MethodHandle hasBlockEntity;
    final MethodHandle liquid;
    final MethodHandle canReplace;
    final MethodHandle material;
    final MethodHandle craftState;
    final MethodHandle createData;
    final MethodHandle heightmaps;
    final MethodHandle lift;
    final MethodHandle firstAvailable;
    final Predicate<Object> motionTest;
    final Predicate<Object> motionNoLeavesTest;
    final Predicate<Object> oceanTest;
    final Predicate<Object> surfaceTest;
    final MethodHandle markUnsaved;
    final MethodHandle pack;
    final MethodHandle visible;
    final MethodHandle blockChanged;
    final MethodHandle holderChanged;
    final MethodHandle beenSent;
    final MethodHandle moveCursor;
    final MethodHandle newCursor;
    final MethodHandle newPos;
    final MethodHandle hasPoi;
    final MethodHandle updatePoi;
    final MethodHandle lightDiffers;
    final MethodHandle sectionPos;
    final MethodHandle sectionStatus;
    final MethodHandle chunkPos;
    final MethodHandle captureBlocks;
    final MethodHandle captureTree;
    final MethodHandle relight;
    final MethodHandle maybeHas;
    final Object motion;
    final Object motionNoLeaves;
    final Object ocean;
    final Object surface;

    private PasteBridge() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        craftWorld = ServerClass.CRAFT_WORLD.load();
        Class<?> serverLevel = ServerClass.SERVER_LEVEL.load();
        Class<?> level = ServerClass.LEVEL.load();
        Class<?> chunkSource = ServerClass.CHUNK_SOURCE.load();
        Class<?> chunkMapClass = ServerClass.CHUNK_MAP.load();
        Class<?> lightClass = ServerClass.LIGHT.load();
        Class<?> chunkAccess = ServerClass.CHUNK_ACCESS.load();
        Class<?> levelChunk = ServerClass.LEVEL_CHUNK.load();
        Class<?> section = ServerClass.SECTION.load();
        Class<?> blockState = ServerClass.BLOCK_STATE.load();
        Class<?> stateBase = ServerClass.STATE_BASE.load();
        Class<?> craftData = ServerClass.CRAFT_DATA.load();
        Class<?> heightmap = ServerClass.HEIGHTMAP.load();
        Class<?> types = ServerClass.HEIGHTMAP_TYPES.load();
        Class<?> chunkPos = ServerClass.CHUNK_POS.load();
        Class<?> holder = ServerClass.CHUNK_HOLDER.load();
        Class<?> blockPos = ServerClass.BLOCK_POS.load();
        Class<?> mutable = ServerClass.MUTABLE_BLOCK_POS.load();
        Class<?> sectionPos = ServerClass.SECTION_POS.load();
        Class<?> poiTypes = ServerClass.POI_TYPES.load();
        Class<?> lightEngine = ServerClass.LIGHT_ENGINE.load();

        getHandle = virtual(lookup, craftWorld, ServerMethod.GET_HANDLE.getId(), MethodType.methodType(serverLevel))
                .asType(MethodType.methodType(Object.class, World.class));
        getChunkSource = virtual(lookup, serverLevel, ServerMethod.GET_CHUNK_SOURCE.getId(), MethodType.methodType(chunkSource))
                .asType(MethodType.methodType(Object.class, Object.class));
        this.chunkMap = lookup.findGetter(chunkSource, ServerField.CHUNK_MAP.getId(), chunkMapClass)
                .asType(MethodType.methodType(Object.class, Object.class));
        getLight = virtual(lookup, chunkSource, ServerMethod.GET_LIGHT_ENGINE.getId(), MethodType.methodType(lightClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        getChunk = virtual(lookup, serverLevel, ServerMethod.GET_CHUNK_IF_LOADED.getId(), MethodType.methodType(levelChunk, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        getSections = virtual(lookup, chunkAccess, ServerMethod.GET_SECTIONS.getId(), MethodType.methodType(section.arrayType()))
                .asType(MethodType.methodType(Object.class, Object.class));
        getMinY = virtual(lookup, chunkAccess, ServerMethod.GET_MIN_Y.getId(), MethodType.methodType(int.class))
                .asType(MethodType.methodType(int.class, Object.class));
        getState = virtual(lookup, section, ServerMethod.GET_BLOCK_STATE.getId(), MethodType.methodType(blockState, int.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class));
        setState = virtual(lookup, section, ServerMethod.SET_BLOCK_STATE.getId(), MethodType.methodType(blockState, int.class, int.class, int.class, blockState, boolean.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class, Object.class, boolean.class));
        onlyAir = virtual(lookup, section, ServerMethod.HAS_ONLY_AIR.getId(), MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        isAir = virtual(lookup, stateBase, ServerMethod.IS_AIR.getId(), MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        hasBlockEntity = virtual(lookup, stateBase, ServerMethod.HAS_BLOCK_ENTITY.getId(), MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        liquid = virtual(lookup, stateBase, ServerMethod.LIQUID.getId(), MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        canReplace = virtual(lookup, stateBase, ServerMethod.CAN_BE_REPLACED.getId(), MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        material = virtual(lookup, blockState, ServerMethod.GET_BUKKIT_MATERIAL.getId(), MethodType.methodType(Material.class))
                .asType(MethodType.methodType(Material.class, Object.class));
        craftState = virtual(lookup, craftData, ServerMethod.GET_STATE.getId(), MethodType.methodType(blockState))
                .asType(MethodType.methodType(Object.class, Object.class));
        createData = lookup.findStatic(craftData, ServerMethod.CREATE_DATA.getId(), MethodType.methodType(craftData, blockState))
                .asType(MethodType.methodType(BlockData.class, Object.class));
        heightmaps = lookup.findGetter(chunkAccess, ServerField.HEIGHTMAPS.getId(), Map.class)
                .asType(MethodType.methodType(Map.class, Object.class));
        lift = virtual(lookup, heightmap, ServerMethod.UPDATE.getId(), MethodType.methodType(boolean.class, int.class, int.class, int.class, blockState))
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class, int.class, Object.class));
        firstAvailable = virtual(lookup, heightmap, ServerMethod.GET_FIRST_AVAILABLE.getId(), MethodType.methodType(int.class, int.class, int.class))
                .asType(MethodType.methodType(int.class, Object.class, int.class, int.class));
        MethodHandle typeOpaque = virtual(lookup, types, ServerMethod.IS_OPAQUE.getId(), MethodType.methodType(Predicate.class))
                .asType(MethodType.methodType(Predicate.class, Object.class));
        markUnsaved = virtual(lookup, levelChunk, ServerMethod.MARK_UNSAVED.getId(), MethodType.methodType(void.class))
                .asType(MethodType.methodType(void.class, Object.class));
        pack = lookup.findStatic(chunkPos, ServerMethod.PACK.getId(), MethodType.methodType(long.class, int.class, int.class));
        visible = virtual(lookup, chunkMapClass, ServerMethod.GET_VISIBLE_CHUNK_IF_PRESENT.getId(), MethodType.methodType(holder, long.class))
                .asType(MethodType.methodType(Object.class, Object.class, long.class));
        blockChanged = virtual(lookup, chunkSource, ServerMethod.BLOCK_CHANGED.getId(), MethodType.methodType(void.class, blockPos))
                .asType(MethodType.methodType(void.class, Object.class, Object.class));
        holderChanged = virtual(lookup, holder, ServerMethod.BLOCK_CHANGED.getId(), MethodType.methodType(boolean.class, blockPos))
                .asType(MethodType.methodType(void.class, Object.class, Object.class));
        beenSent = virtual(lookup, holder, ServerMethod.MOONRISE_HAS_CHUNK_BEEN_SENT.getId(), MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        newCursor = lookup.findConstructor(mutable, MethodType.methodType(void.class))
                .asType(MethodType.methodType(Object.class));
        moveCursor = virtual(lookup, mutable, ServerMethod.SET.getId(), MethodType.methodType(mutable, int.class, int.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class, int.class));
        newPos = lookup.findConstructor(blockPos, MethodType.methodType(void.class, int.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, int.class, int.class, int.class));
        hasPoi = lookup.findStatic(poiTypes, ServerMethod.HAS_POI.getId(), MethodType.methodType(boolean.class, blockState))
                .asType(MethodType.methodType(boolean.class, Object.class));
        updatePoi = virtual(lookup, serverLevel, ServerMethod.UPDATE_POI_ON_BLOCK_STATE_CHANGE.getId(), MethodType.methodType(void.class, blockPos, blockState, blockState))
                .asType(MethodType.methodType(void.class, Object.class, Object.class, Object.class, Object.class));
        lightDiffers = lookup.findStatic(lightEngine, ServerMethod.HAS_DIFFERENT_LIGHT_PROPERTIES.getId(), MethodType.methodType(boolean.class, blockState, blockState))
                .asType(MethodType.methodType(boolean.class, Object.class, Object.class));
        this.sectionPos = lookup.findStatic(sectionPos, ServerMethod.OF.getId(), MethodType.methodType(sectionPos, int.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, int.class, int.class, int.class));
        sectionStatus = virtual(lookup, lightClass, ServerMethod.UPDATE_SECTION_STATUS.getId(), MethodType.methodType(void.class, sectionPos, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, Object.class, boolean.class));
        this.chunkPos = virtual(lookup, chunkAccess, ServerMethod.GET_POS.getId(), MethodType.methodType(chunkPos))
                .asType(MethodType.methodType(Object.class, Object.class));
        captureBlocks = optionalBoolean(lookup, level, ServerField.CAPTURE_BLOCK_STATES.getId());
        captureTree = optionalBoolean(lookup, level, ServerField.CAPTURE_TREE_GENERATION.getId());
        MethodHandle relightHandle = null;
        try {
            relightHandle = virtual(
                    lookup,
                    lightClass,
                    ServerMethod.STARLIGHT_SERVER_RELIGHT_CHUNKS.getId(),
                    MethodType.methodType(int.class, java.util.Collection.class, Consumer.class, IntConsumer.class)
            ).asType(MethodType.methodType(void.class, Object.class, List.class, Consumer.class, IntConsumer.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            LOG.warning("section paste has no bulk relight; edited chunks keep their old light until a reload");
        }
        relight = relightHandle;
        MethodHandle maybeHasHandle = null;
        try {
            maybeHasHandle = virtual(lookup, section, ServerMethod.MAYBE_HAS.getId(), MethodType.methodType(boolean.class, Predicate.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, Predicate.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {}
        maybeHas = maybeHasHandle;

        motion = enumConst(types, HeightmapType.MOTION_BLOCKING.name());
        motionNoLeaves = enumConst(types, HeightmapType.MOTION_BLOCKING_NO_LEAVES.name());
        ocean = enumConst(types, HeightmapType.OCEAN_FLOOR.name());
        surface = enumConst(types, HeightmapType.WORLD_SURFACE.name());
        motionTest = predicate(typeOpaque, motion);
        motionNoLeavesTest = predicate(typeOpaque, motionNoLeaves);
        oceanTest = predicate(typeOpaque, ocean);
        surfaceTest = predicate(typeOpaque, surface);
    }

    @SuppressWarnings("unchecked")
    private static Predicate<Object> predicate(MethodHandle typeOpaque, Object type) throws Throwable {
        return (Predicate<Object>) typeOpaque.invokeExact(type);
    }

    static PasteBridge load() {
        PasteBridge ready = bridge;
        if (ready != null || unavailable) return ready;
        synchronized (PasteBridge.class) {
            if (bridge != null || unavailable) return bridge;
            try {
                bridge = new PasteBridge();
                return bridge;
            } catch (Throwable failure) {
                fail(failure);
                return null;
            }
        }
    }

    static void fail(Throwable failure) {
        if (unavailable) return;
        unavailable = true;
        bridge = null;
        LOG.log(Level.WARNING, "direct section paste is unavailable; rollbacks will write blocks through bukkit", failure);
    }

    private static MethodHandle optionalBoolean(MethodHandles.Lookup lookup, Class<?> owner, String name) {
        try {
            return lookup.findGetter(owner, name, boolean.class)
                    .asType(MethodType.methodType(boolean.class, Object.class));
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static MethodHandle virtual(MethodHandles.Lookup lookup, Class<?> owner, String name, MethodType type) throws ReflectiveOperationException {
        return lookup.findVirtual(owner, name, type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConst(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }
}
