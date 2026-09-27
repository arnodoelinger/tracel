package com.tracel.plugin.rollback.structure.block;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes block states straight into a chunk section.
 *
 * <p>{@code Bukkit} {@code setBlockData} pays for a collision test, a pathfinding scan, a light ticket and
 * a chunk lookup on every block. A rollback is thousands of those. This keeps the section palette,
 * the four surface heightmaps and one client-dirty bit per chunk, then relights the dirty chunks once.
 *
 * <p>No Minecraft type appears in this class's constant pool. The server classes are resolved when
 * a paste is opened, so unit tests can load it without a server. If the lookup fails, callers fall
 * back to {@code Bukkit}.
 */
public final class PalettePaste {
    private static final Logger LOG = Logger.getLogger("Tracel");
    private static final ThreadLocal<PalettePaste> CURRENT = new ThreadLocal<>();
    private static final int AIR = 1;
    private static final int ENTITY = 2;
    private static final int POI = 4;
    private static final int MOTION = 8;
    private static final int NO_LEAVES = 16;
    private static final int OCEAN = 32;
    private static final int SURFACE = 64;
    private static final int EAST = 1;
    private static final int WEST = 2;
    private static final int SOUTH = 4;
    private static final int NORTH = 8;
    private static final int SE = 16;
    private static final int SW = 32;
    private static final int NE = 64;
    private static final int NW = 128;
    private static final int ALL_EDGES = EAST | WEST | SOUTH | NORTH | SE | SW | NE | NW;
    public final World world;
    private final PasteBridge nms;
    private final Object level;
    private final Object chunkSource;
    private final Object chunkMap;
    private final Object light;
    private final Object cursor;
    private final LongMap<PasteChunk> chunks = new LongMap<>();
    private final LongMap<Object> relight = new LongMap<>();
    private final List<Object> relightChunks = new ArrayList<>();
    private final List<PasteChunk> dirty = new ArrayList<>();
    private final IdentityHashMap<Object, BlockData> views = new IdentityHashMap<>();
    private final IdentityHashMap<Object, String> strings = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Integer> traits = new IdentityHashMap<>();
    private final IdentityHashMap<BlockData, Object> nmsOf = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Material> materials = new IdentityHashMap<>();
    public int written;
    public Object lastRead;
    private int lastCx = Integer.MIN_VALUE;
    private int lastCz;
    private PasteChunk lastChunk;
    private boolean dead;
    private byte capture; // 0 unknown, 1 capturing, -1 not. Read once per paste

    /** Paste currently bound to this thread, or {@code null}. */
    public static PalettePaste current() {
        return CURRENT.get();
    }

    /**
     * Open a paste for {@code world}, or {@code null} when the server classes are not the ones we know
     * or the world is not a live server world.
     */
    public static PalettePaste tryOpen(World world) {
        PasteBridge ready = PasteBridge.load();
        if (ready == null || !ready.craftWorld.isInstance(world)) return null;
        try {
            Object level = ready.getHandle.invokeExact(world);
            if (level == null) return null;
            Object source = ready.getChunkSource.invokeExact(level);
            Object map = ready.chunkMap.invokeExact(source);
            Object light = ready.getLight.invokeExact(source);
            Object cursor = ready.newCursor.invokeExact();
            return new PalettePaste(ready, world, level, source, map, light, cursor);
        } catch (Throwable failure) {
            PasteBridge.fail(failure);
            return null;
        }
    }

    /** Section index for a block y, given the chunk's minimum section y. */
    public static int sectionIndex(int blockY, int minSectionY) {
        return (blockY >> 4) - minSectionY;
    }

    /** Bind this paste to the calling thread so nested block edits (leaves, fluids) hit it too. */
    public void bind() {
        CURRENT.set(this);
    }

    /** Relight dirty chunks and drop the thread binding. Safe to call twice. */
    public void close() {
        try {
            flush();
        } finally {
            if (CURRENT.get() == this) CURRENT.remove();
        }
    }

    /** World capture (trees, structure blocks) must keep going through vanilla. */
    public boolean capturing() {
        if (dead) return true;
        if (capture != 0) return capture > 0;
        if (nms.captureBlocks == null && nms.captureTree == null) {
            capture = -1;
            return false;
        }
        try {
            boolean on = nms.captureBlocks != null && (boolean) nms.captureBlocks.invokeExact(level);
            if (!on && nms.captureTree != null) on = (boolean) nms.captureTree.invokeExact(level);
            capture = (byte) (on ? 1 : -1);
            return on;
        } catch (Throwable failure) {
            die(failure);
            return true;
        }
    }

    /**
     * Live state at the block, or {@code null} when the chunk is not loaded or the height is outside.
     */
    public Object read(int x, int y, int z) {
        if (dead) return null;
        try {
            PasteChunk chunk = chunk(x >> 4, z >> 4);
            if (chunk == null) return null;
            Object section = chunk.section(y);
            if (section == null) return null;
            return nms.getState.invokeExact(section, x & 15, y & 15, z & 15);
        } catch (Throwable failure) {
            die(failure);
            return null;
        }
    }

    /**
     * NMS state of a {@code Bukkit} block data, or {@code null} when it is not a server block data.
     */
    public Object stateOf(BlockData data) {
        if (data == null || dead) return null;
        Object cached = nmsOf.get(data);
        if (cached != null) return cached;
        try {
            Object state = nms.craftState.invokeExact((Object) data);
            if (state != null) nmsOf.put(data, state);
            return state;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Whether it's an air. */
    public boolean isAir(Object state) {
        try {
            return (traits(state) & AIR) != 0;
        } catch (Throwable failure) {
            die(failure);
            return false;
        }
    }

    /** Whether it's a block entity. */
    public boolean hasBlockEntity(Object state) {
        try {
            return (traits(state) & ENTITY) != 0;
        } catch (Throwable failure) {
            die(failure);
            return true;
        }
    }

    /** Whether it's a liquid. */
    public boolean liquid(Object state) {
        try {
            return (boolean) nms.liquid.invokeExact(state);
        } catch (Throwable failure) {
            die(failure);
            return false;
        }
    }

    /** Whether it can be replaced. */
    public boolean canReplace(Object state) {
        try {
            return (boolean) nms.canReplace.invokeExact(state);
        } catch (Throwable failure) {
            die(failure);
            return false;
        }
    }

    /** Whether it's a material. */
    public Material material(Object state) {
        Material cached = materials.get(state);
        if (cached != null) return cached;
        try {
            Material value = (Material) nms.material.invokeExact(state);
            if (value != null) materials.put(state, value);
            return value == null ? Material.AIR : value;
        } catch (Throwable failure) {
            die(failure);
            return Material.AIR;
        }
    }

    /** Whether it's ageable. */
    public boolean ageable(Object state) {
        return view(state) instanceof Ageable;
    }

    /** Whether it's waterlogged. */
    public boolean waterlogged(Object state) {
        BlockData data = view(state);
        return data instanceof Waterlogged logged && logged.isWaterlogged();
    }

    /** Whether it's a string. */
    public String asString(Object state) {
        String cached = strings.get(state);
        if (cached != null) return cached;
        String value = view(state).getAsString();
        strings.put(state, value);
        return value;
    }

    /**
     * Put {@code next} at the block when neither side is a block entity.
     *
     * @return {@code false} when the caller must use Bukkit instead
     */
    public boolean replace(int x, int y, int z, BlockData data) {
        if (capturing()) return false;
        Object next = stateOf(data);
        if (next == null || hasBlockEntity(next)) return false;
        Object live = read(x, y, z);
        if (live == null || hasBlockEntity(live)) return false;
        if (live == next) return true;
        return commit(x, y, z, live, next);
    }

    /**
     * Write {@code next} over whatever is there now.
     *
     * @return {@code false} when the section was not written
     */
    public boolean put(int x, int y, int z, Object next) {
        return commit(x, y, z, null, next);
    }

    /**
     * Write {@code next} when the caller already read [previous].
     *
     * @return {@code false} when the section was not written
     */
    public boolean commit(int x, int y, int z, Object previous, Object next) {
        if (dead || next == null) return false;
        try {
            return put0(x, y, z, previous, next);
        } catch (Throwable failure) {
            die(failure);
            return false;
        }
    }

    /**
     * Common rollback case: the block is still the expected plain state, so write the target.
     *
     * @return 0 unchanged, 1 written, -1 use {@code Bukkit}, -2 the block drifted and needs the slow check
     */
    public int placeFast(
            int x,
            int y,
            int z,
            Object target,
            Object expected,
            boolean targetAir,
            boolean expectedAir
    ) {
        if (dead || target == null) return -1;
        try {
            int targetTraits = traits(target);
            if ((targetTraits & ENTITY) != 0) return -1;
            PasteChunk chunk = chunk(x >> 4, z >> 4);
            if (chunk == null) return -1;
            Object section = chunk.section(y);
            if (section == null) return -1;
            Object live = nms.getState.invokeExact(section, x & 15, y & 15, z & 15);
            lastRead = live;
            int liveTraits = traits(live);
            if ((liveTraits & ENTITY) != 0) return -1;
            boolean liveAir = (liveTraits & AIR) != 0;
            if (targetAir ? liveAir : live == target) return 0;
            boolean matches = expectedAir ? liveAir : live == expected;
            if (!matches || (!expectedAir && expected == null)) return -2;
            writePlain(chunk, section, x, y, z, live, liveTraits, target, targetTraits);
            return 1;
        } catch (Throwable failure) {
            die(failure);
            return -1;
        }
    }

    private PalettePaste(PasteBridge nms, World world, Object level, Object chunkSource, Object chunkMap, Object light, Object cursor) {
        this.nms = nms;
        this.world = world;
        this.level = level;
        this.chunkSource = chunkSource;
        this.chunkMap = chunkMap;
        this.light = light;
        this.cursor = cursor;
    }

    private static int reach(int localX, int localZ) {
        int edges = 0;
        if (localX >= 1) edges |= EAST;
        if (localX <= 14) edges |= WEST;
        if (localZ >= 1) edges |= SOUTH;
        if (localZ <= 14) edges |= NORTH;
        if ((16 - localX) + (16 - localZ) <= 15) edges |= SE;
        if ((localX + 1) + (16 - localZ) <= 15) edges |= SW;
        if ((16 - localX) + (localZ + 1) <= 15) edges |= NE;
        if ((localX + 1) + (localZ + 1) <= 15) edges |= NW;
        return edges;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private boolean put0(int x, int y, int z, Object previous, Object next) throws Throwable {
        PasteChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk == null) return false;
        Object section = chunk.section(y);
        if (section == null) return false;
        Object before = previous != null ? previous : nms.getState.invokeExact(section, x & 15, y & 15, z & 15);
        if (before == next) return true;
        int beforeTraits = traits(before);
        int nextTraits = traits(next);
        if ((beforeTraits & ENTITY) != 0 || (nextTraits & ENTITY) != 0) return false;
        writePlain(chunk, section, x, y, z, before, beforeTraits, next, nextTraits);
        return true;
    }

    private void writePlain(
            PasteChunk chunk,
            Object section,
            int x,
            int y,
            int z,
            Object before,
            int beforeTraits,
            Object next,
            int nextTraits
    ) throws Throwable {
        int localX = x & 15;
        int localZ = z & 15;
        Object placed = (Object) nms.setState.invokeExact(section, localX, y & 15, localZ, next, true);
        if (placed != before && placed != next) {
            before = placed;
            beforeTraits = traits(before);
        }
        noteColumn(chunk, localX, y, localZ);
        if (!chunk.unsaved) {
            nms.markUnsaved.invokeExact(chunk.nms);
            chunk.unsaved = true;
        }
        queueChange(chunk, localX, localZ, y);
        if ((beforeTraits & POI) != 0 || (nextTraits & POI) != 0) {
            Object pos = nms.newPos.invokeExact(x, y, z);
            nms.updatePoi.invokeExact(level, pos, before, next);
        }
        if (chunk.lightEdges != ALL_EDGES) {
            boolean affects = (beforeTraits & AIR) != (nextTraits & AIR);
            if (!affects) affects = (boolean) nms.lightDiffers.invokeExact(before, next);
            if (affects) chunk.lightEdges |= reach(localX, localZ);
        }
        written++;
    }

    private int traits(Object state) throws Throwable {
        Integer cached = traits.get(state);
        if (cached != null) return cached;
        int value = 0;
        if ((boolean) nms.isAir.invokeExact(state)) value |= AIR;
        if ((boolean) nms.hasBlockEntity.invokeExact(state)) value |= ENTITY;
        if ((boolean) nms.hasPoi.invokeExact(state)) value |= POI;
        if (nms.motionTest.test(state)) value |= MOTION;
        if (nms.motionNoLeavesTest.test(state)) value |= NO_LEAVES;
        if (nms.oceanTest.test(state)) value |= OCEAN;
        if (nms.surfaceTest.test(state)) value |= SURFACE;
        traits.put(state, value);
        return value;
    }

    private void noteColumn(PasteChunk chunk, int localX, int y, int localZ) {
        int column = (localZ << 4) | localX;
        chunk.columns[column >> 6] |= 1L << (column & 63);
        if (y <= chunk.columnTop[column]) return;
        chunk.columnTop[column] = y;
        if (chunk.listed) return;
        chunk.listed = true;
        dirty.add(chunk);
    }

    private void applyHeightmaps() throws Throwable {
        for (PasteChunk chunk : dirty) {
            for (int column = 0; column < 256; column++) {
                if ((chunk.columns[column >> 6] & (1L << (column & 63))) == 0) continue;
                settleColumn(chunk, column & 15, column >> 4, chunk.columnTop[column]);
            }
        }
    }

    private void settleColumn(PasteChunk chunk, int localX, int localZ, int top) throws Throwable {
        int lowest = chunk.minSection << 4;
        int motionAt = surfaceOf(chunk.motion, localX, localZ);
        int leavesAt = surfaceOf(chunk.motionNoLeaves, localX, localZ);
        int oceanAt = surfaceOf(chunk.ocean, localX, localZ);
        int surfaceAt = surfaceOf(chunk.surface, localX, localZ);
        boolean motion = motionAt != Integer.MIN_VALUE;
        boolean leaves = leavesAt != Integer.MIN_VALUE;
        boolean ocean = oceanAt != Integer.MIN_VALUE;
        boolean surface = surfaceAt != Integer.MIN_VALUE;
        for (int y = top; y >= lowest && (motion || leaves || ocean || surface); y--) {
            if ((motion && y >= motionAt - 1) || (leaves && y >= leavesAt - 1)
                    || (ocean && y >= oceanAt - 1) || (surface && y >= surfaceAt - 1)) {
                Object state = chunk.stateAt(nms, localX, y, localZ);
                if (state == null) return;
                int bits = traits(state);
                if (motion && y >= motionAt - 1 && (((bits & MOTION) != 0) || y == motionAt - 1)) {
                    nms.lift.invokeExact(chunk.motion, localX, y, localZ, state);
                    motion = false;
                }
                if (leaves && y >= leavesAt - 1 && (((bits & NO_LEAVES) != 0) || y == leavesAt - 1)) {
                    nms.lift.invokeExact(chunk.motionNoLeaves, localX, y, localZ, state);
                    leaves = false;
                }
                if (ocean && y >= oceanAt - 1 && (((bits & OCEAN) != 0) || y == oceanAt - 1)) {
                    nms.lift.invokeExact(chunk.ocean, localX, y, localZ, state);
                    ocean = false;
                }
                if (surface && y >= surfaceAt - 1 && (((bits & SURFACE) != 0) || y == surfaceAt - 1)) {
                    nms.lift.invokeExact(chunk.surface, localX, y, localZ, state);
                    surface = false;
                }
            } else {
                return;
            }
        }
    }

    private int surfaceOf(Object heightmap, int localX, int localZ) throws Throwable {
        if (heightmap == null) return Integer.MIN_VALUE;
        return (int) nms.firstAvailable.invokeExact(heightmap, localX, localZ);
    }

    private void queueChange(PasteChunk chunk, int localX, int localZ, int y) throws Throwable {
        if (chunk.holder == null) return;
        if (chunk.watch == 0) {
            chunk.watch = (boolean) nms.beenSent.invokeExact(chunk.holder) ? (byte) 1 : (byte) -1;
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

    private void flushNotifications() throws Throwable {
        for (PasteChunk chunk : dirty) {
            int count = chunk.changeCount;
            if (count == 0) continue;
            int baseX = chunk.cx << 4;
            int baseZ = chunk.cz << 4;
            int[] changes = chunk.changes;
            for (int i = 0; i < count; i++) {
                int packed = changes[i];
                int x = baseX | (packed & 15);
                int z = baseZ | ((packed >> 4) & 15);
                int y = packed >> 8;
                nms.moveCursor.invokeExact(cursor, x, y, z);
                if (!chunk.armed) {
                    nms.blockChanged.invokeExact(chunkSource, cursor);
                    chunk.armed = true;
                } else {
                    nms.holderChanged.invokeExact(chunk.holder, cursor);
                }
            }
        }
    }

    private BlockData view(Object state) {
        BlockData cached = views.get(state);
        if (cached != null) return cached;
        try {
            BlockData created = (BlockData) nms.createData.invokeExact(state);
            views.put(state, created);
            return created;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void flush() {
        try {
            flushNotifications();
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "client updates after a rollback paste failed", failure);
        }
        try {
            queueRelight();
            applyHeightmaps();
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "heightmaps after a rollback paste failed", failure);
        } finally {
            dirty.clear();
        }
        if (relightChunks.isEmpty() || nms.relight == null) return;
        try {
            nms.relight.invokeExact(light, relightChunks, (Consumer<Object>) null, (IntConsumer) null);
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "chunk relight after a rollback paste failed", failure);
        } finally {
            relightChunks.clear();
        }
    }

    private void queueRelight() {
        for (PasteChunk chunk : dirty) {
            int edges = chunk.lightEdges;
            if (edges == 0) continue;
            markLight(chunk.nms, chunk.cx, chunk.cz);
            if ((edges & EAST) != 0) markLight(null, chunk.cx + 1, chunk.cz);
            if ((edges & WEST) != 0) markLight(null, chunk.cx - 1, chunk.cz);
            if ((edges & SOUTH) != 0) markLight(null, chunk.cx, chunk.cz + 1);
            if ((edges & NORTH) != 0) markLight(null, chunk.cx, chunk.cz - 1);
            if ((edges & SE) != 0) markLight(null, chunk.cx + 1, chunk.cz + 1);
            if ((edges & SW) != 0) markLight(null, chunk.cx - 1, chunk.cz + 1);
            if ((edges & NE) != 0) markLight(null, chunk.cx + 1, chunk.cz - 1);
            if ((edges & NW) != 0) markLight(null, chunk.cx - 1, chunk.cz - 1);
        }
    }

    private void markLight(Object knownChunk, int cx, int cz) {
        long key = key(cx, cz);
        if (relight.get(key) != null) return;
        try {
            Object chunk = knownChunk != null ? knownChunk : nms.getChunk.invokeExact(level, cx, cz);
            if (chunk == null) return;
            Object pos = nms.chunkPos.invokeExact(chunk);
            relight.put(key, pos);
            relightChunks.add(pos);
        } catch (Throwable failure) {
            LOG.log(Level.FINE, "a neighbour chunk was left out of the relight", failure);
        }
    }

    private PasteChunk chunk(int cx, int cz) throws Throwable {
        if (cx == lastCx && cz == lastCz) return lastChunk;
        long key = key(cx, cz);
        PasteChunk found = chunks.get(key);
        if (found == null) {
            Object nmsChunk = nms.getChunk.invokeExact(level, cx, cz);
            if (nmsChunk == null) return null;
            found = PasteChunk.open(nms, nmsChunk, chunkMap, cx, cz);
            chunks.put(key, found);
        }
        lastCx = cx;
        lastCz = cz;
        lastChunk = found;
        return found;
    }

    private void die(Throwable failure) {
        if (dead) return;
        dead = true;
        LOG.log(Level.WARNING, "section paste failed mid-write; the rest of this group uses bukkit", failure);
    }
}
