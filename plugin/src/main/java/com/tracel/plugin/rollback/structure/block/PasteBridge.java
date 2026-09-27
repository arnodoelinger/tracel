package com.tracel.plugin.rollback.structure.block;

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

/** Server methods used to read and write a chunk section. */
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
    final Object motion;
    final Object motionNoLeaves;
    final Object ocean;
    final Object surface;

    private PasteBridge() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
        Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> level = Class.forName("net.minecraft.world.level.Level");
        Class<?> chunkSource = Class.forName("net.minecraft.server.level.ServerChunkCache");
        Class<?> chunkMapClass = Class.forName("net.minecraft.server.level.ChunkMap");
        Class<?> lightClass = Class.forName("net.minecraft.server.level.ThreadedLevelLightEngine");
        Class<?> chunkAccess = Class.forName("net.minecraft.world.level.chunk.ChunkAccess");
        Class<?> levelChunk = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
        Class<?> section = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection");
        Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState");
        Class<?> stateBase = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
        Class<?> craftData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
        Class<?> heightmap = Class.forName("net.minecraft.world.level.levelgen.Heightmap");
        Class<?> types = Class.forName("net.minecraft.world.level.levelgen.Heightmap$Types");
        Class<?> chunkPos = Class.forName("net.minecraft.world.level.ChunkPos");
        Class<?> holder = Class.forName("net.minecraft.server.level.ChunkHolder");
        Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
        Class<?> mutable = Class.forName("net.minecraft.core.BlockPos$MutableBlockPos");
        Class<?> sectionPos = Class.forName("net.minecraft.core.SectionPos");
        Class<?> poiTypes = Class.forName("net.minecraft.world.entity.ai.village.poi.PoiTypes");
        Class<?> lightEngine = Class.forName("net.minecraft.world.level.lighting.LightEngine");

        getHandle = virtual(lookup, craftWorld, "getHandle", MethodType.methodType(serverLevel))
                .asType(MethodType.methodType(Object.class, World.class));
        getChunkSource = virtual(lookup, serverLevel, "getChunkSource", MethodType.methodType(chunkSource))
                .asType(MethodType.methodType(Object.class, Object.class));
        this.chunkMap = lookup.findGetter(chunkSource, "chunkMap", chunkMapClass)
                .asType(MethodType.methodType(Object.class, Object.class));
        getLight = virtual(lookup, chunkSource, "getLightEngine", MethodType.methodType(lightClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        getChunk = virtual(lookup, serverLevel, "getChunkIfLoaded", MethodType.methodType(levelChunk, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        getSections = virtual(lookup, chunkAccess, "getSections", MethodType.methodType(section.arrayType()))
                .asType(MethodType.methodType(Object.class, Object.class));
        getMinY = virtual(lookup, chunkAccess, "getMinY", MethodType.methodType(int.class))
                .asType(MethodType.methodType(int.class, Object.class));
        getState = virtual(lookup, section, "getBlockState", MethodType.methodType(blockState, int.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class));
        setState = virtual(lookup, section, "setBlockState", MethodType.methodType(blockState, int.class, int.class, int.class, blockState, boolean.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class, Object.class, boolean.class));
        onlyAir = virtual(lookup, section, "hasOnlyAir", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        isAir = virtual(lookup, stateBase, "isAir", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        hasBlockEntity = virtual(lookup, stateBase, "hasBlockEntity", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        liquid = virtual(lookup, stateBase, "liquid", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        canReplace = virtual(lookup, stateBase, "canBeReplaced", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        material = virtual(lookup, blockState, "getBukkitMaterial", MethodType.methodType(Material.class))
                .asType(MethodType.methodType(Material.class, Object.class));
        craftState = virtual(lookup, craftData, "getState", MethodType.methodType(blockState))
                .asType(MethodType.methodType(Object.class, Object.class));
        createData = lookup.findStatic(craftData, "createData", MethodType.methodType(craftData, blockState))
                .asType(MethodType.methodType(BlockData.class, Object.class));
        heightmaps = lookup.findGetter(chunkAccess, "heightmaps", Map.class)
                .asType(MethodType.methodType(Map.class, Object.class));
        lift = virtual(lookup, heightmap, "update", MethodType.methodType(boolean.class, int.class, int.class, int.class, blockState))
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class, int.class, Object.class));
        firstAvailable = virtual(lookup, heightmap, "getFirstAvailable", MethodType.methodType(int.class, int.class, int.class))
                .asType(MethodType.methodType(int.class, Object.class, int.class, int.class));
        MethodHandle typeOpaque = virtual(lookup, types, "isOpaque", MethodType.methodType(Predicate.class))
                .asType(MethodType.methodType(Predicate.class, Object.class));
        markUnsaved = virtual(lookup, levelChunk, "markUnsaved", MethodType.methodType(void.class))
                .asType(MethodType.methodType(void.class, Object.class));
        pack = lookup.findStatic(chunkPos, "pack", MethodType.methodType(long.class, int.class, int.class));
        visible = virtual(lookup, chunkMapClass, "getVisibleChunkIfPresent", MethodType.methodType(holder, long.class))
                .asType(MethodType.methodType(Object.class, Object.class, long.class));
        blockChanged = virtual(lookup, chunkSource, "blockChanged", MethodType.methodType(void.class, blockPos))
                .asType(MethodType.methodType(void.class, Object.class, Object.class));
        holderChanged = virtual(lookup, holder, "blockChanged", MethodType.methodType(boolean.class, blockPos))
                .asType(MethodType.methodType(void.class, Object.class, Object.class));
        beenSent = virtual(lookup, holder, "moonrise$hasChunkBeenSent", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        newCursor = lookup.findConstructor(mutable, MethodType.methodType(void.class))
                .asType(MethodType.methodType(Object.class));
        moveCursor = virtual(lookup, mutable, "set", MethodType.methodType(mutable, int.class, int.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class, int.class));
        newPos = lookup.findConstructor(blockPos, MethodType.methodType(void.class, int.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, int.class, int.class, int.class));
        hasPoi = lookup.findStatic(poiTypes, "hasPoi", MethodType.methodType(boolean.class, blockState))
                .asType(MethodType.methodType(boolean.class, Object.class));
        updatePoi = virtual(lookup, serverLevel, "updatePOIOnBlockStateChange", MethodType.methodType(void.class, blockPos, blockState, blockState))
                .asType(MethodType.methodType(void.class, Object.class, Object.class, Object.class, Object.class));
        lightDiffers = lookup.findStatic(lightEngine, "hasDifferentLightProperties", MethodType.methodType(boolean.class, blockState, blockState))
                .asType(MethodType.methodType(boolean.class, Object.class, Object.class));
        this.sectionPos = lookup.findStatic(sectionPos, "of", MethodType.methodType(sectionPos, int.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, int.class, int.class, int.class));
        sectionStatus = virtual(lookup, lightClass, "updateSectionStatus", MethodType.methodType(void.class, sectionPos, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, Object.class, boolean.class));
        this.chunkPos = virtual(lookup, chunkAccess, "getPos", MethodType.methodType(chunkPos))
                .asType(MethodType.methodType(Object.class, Object.class));
        captureBlocks = optionalBoolean(lookup, level, "captureBlockStates");
        captureTree = optionalBoolean(lookup, level, "captureTreeGeneration");
        MethodHandle relightHandle = null;
        try {
            relightHandle = virtual(
                    lookup,
                    lightClass,
                    "starlight$serverRelightChunks",
                    MethodType.methodType(int.class, java.util.Collection.class, Consumer.class, IntConsumer.class)
            ).asType(MethodType.methodType(void.class, Object.class, List.class, Consumer.class, IntConsumer.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            LOG.warning("section paste has no bulk relight; edited chunks keep their old light until a reload");
        }
        relight = relightHandle;

        motion = enumConst(types, "MOTION_BLOCKING");
        motionNoLeaves = enumConst(types, "MOTION_BLOCKING_NO_LEAVES");
        ocean = enumConst(types, "OCEAN_FLOOR");
        surface = enumConst(types, "WORLD_SURFACE");
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

    /** {@code Paper} keeps these on {@code Level}. {@code Folia} does not, and a missing field must not disable the paste. */
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
