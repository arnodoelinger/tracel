package com.tracel.plugin.adapter.rollback.structure.block;

import com.tracel.plugin.adapter.rollback.structure.block.bridge.ChunkHandles;
import com.tracel.plugin.adapter.rollback.structure.block.bridge.LevelHandles;
import com.tracel.plugin.adapter.rollback.structure.block.bridge.NotifyHandles;
import com.tracel.plugin.adapter.rollback.structure.block.bridge.PasteBridge;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
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

    public final World world;
    private final Object level;
    private final Fuse fuse = new Fuse();
    private final ChunkCache chunks;
    private final FireProbe fire;
    private final ChangeNotifier notifier;
    private final RelightQueue relight;
    private final PasteFinisher finisher;

    private final List<PasteChunk> dirty = new ArrayList<>();
    private final StateTraits traits = new StateTraits(fuse);
    private final HeightmapSettler heightmaps = new HeightmapSettler(traits, dirty);

    public Object lastRead;

    public int written;

    private byte capture; // 0 unknown, 1 capturing, -1 not. Read once per paste

    /**
     * Result of {@link #placeFast}.
     */
    public enum Fast {
        /**
         * Already the target state.
         */
        UNCHANGED,

        /**
         * Written into the section palette.
         */
        WRITTEN,

        /**
         * Paste cannot handle this cell; use {@code Bukkit}.
         */
        BUKKIT,

        /**
         * Live block is not the expected one; the slow check decides.
         */
        DRIFTED
    }

    /**
     * Paste currently bound to this thread, or {@code null}.
     */
    public static PalettePaste current() {
        return CURRENT.get();
    }

    /**
     * Open a paste for {@code world}, or {@code null} when the server classes are not the ones we know
     * or the world is not a live server world.
     */
    public static PalettePaste tryOpen(World world) {
        if (!PasteBridge.ready() || !LevelHandles.CRAFT_WORLD.isInstance(world)) return null;
        try {
            Object level = LevelHandles.GET_HANDLE.invokeExact(world);
            if (level == null) return null;
            Object source = LevelHandles.GET_CHUNK_SOURCE.invokeExact(level);
            Object map = LevelHandles.CHUNK_MAP.invokeExact(source);
            Object light = LevelHandles.GET_LIGHT.invokeExact(source);
            Object cursor = NotifyHandles.NEW_CURSOR.invokeExact();
            return new PalettePaste(world, level, source, map, light, cursor);
        } catch (Throwable failure) {
            PasteBridge.fail(failure);
            return null;
        }
    }

    /**
     * Section index for a block y, given the chunk's minimum section y.
     */
    public static int sectionIndex(int blockY, int minSectionY) {
        return (blockY >> 4) - minSectionY;
    }

    /**
     * Bind this paste to the calling thread so nested block edits (leaves, fluids) hit it too.
     */
    public void bind() {
        CURRENT.set(this);
    }

    /**
     * End of one turn of a paste that spans several ticks: tell the clients what changed so far and let go of the
     * thread, but keep the relight and heightmap work for {@link #close()}, so a chunk is relit once however many
     * turns touched it.
     *
     * <p>The chunk cache is dropped. Between ticks a chunk can be unloaded and come back as another object, and a
     * write into the old one would be lost. A chunk touched again after {@link #resume()} is opened afresh and
     * listed again; the relight set and the heightmap pass do not mind seeing it twice.
     */
    public void pause() {
        try {
            notifier.flush();
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "client updates during a rollback paste failed.", failure);
        }
        fire.reset();
        chunks.clear();
        if (CURRENT.get() == this) CURRENT.remove();
    }

    /**
     * Bind to the calling thread again after {@link #pause()}.
     */
    public void resume() {
        bind();
    }

    /**
     * Relight dirty chunks and drop the thread binding.
     *
     * <p>Safe to call twice.
     */
    public void close() {
        try {
            finisher.run(Long.MAX_VALUE);
        } finally {
            if (CURRENT.get() == this) CURRENT.remove();
        }
    }

    /**
     * {@link #close()} in slices: does what fits in {@code budgetNanos} of the heightmap and relight work and says
     * whether any is left. The caller {@link #pause() pauses}, waits for its next tick and {@link #resume() resumes}
     * between calls, and still {@link #close() closes} at the end, which is then nothing to do.
     *
     * @return {@code true} when everything is done
     */
    public boolean closeSome(long budgetNanos) {
        return finisher.run(budgetNanos);
    }

    /**
     * World capture (trees, structure blocks) must keep going through vanilla.
     */
    public boolean capturing() {
        if (fuse.dead) return true;
        if (capture != 0) return capture > 0;
        if (LevelHandles.CAPTURE_BLOCKS == null && LevelHandles.CAPTURE_TREE == null) {
            capture = -1;
            return false;
        }
        try {
            boolean on = LevelHandles.CAPTURE_BLOCKS != null && (boolean) LevelHandles.CAPTURE_BLOCKS.invokeExact(level);
            if (!on && LevelHandles.CAPTURE_TREE != null) on = (boolean) LevelHandles.CAPTURE_TREE.invokeExact(level);
            capture = (byte) (on ? 1 : -1);
            return on;
        } catch (Throwable failure) {
            fuse.trip(failure);
            return true;
        }
    }

    /**
     * Live state at the block, or {@code null} when the chunk is not loaded or the height is outside.
     */
    public Object read(int x, int y, int z) {
        if (fuse.dead) return null;
        try {
            PasteChunk chunk = chunks.get(x >> 4, z >> 4);
            if (chunk == null) return null;
            Object section = chunk.section(y);
            if (section == null) return null;
            return ChunkHandles.GET_STATE.invokeExact(section, x & 15, y & 15, z & 15);
        } catch (Throwable failure) {
            fuse.trip(failure);
            return null;
        }
    }

    /**
     * Whether the section holding the block certainly has no fire in it.
     */
    public boolean fireFreeAt(int x, int y, int z) {
        return fire.freeAt(x, y, z);
    }

    /**
     * {@link #fireFreeAt} for the block and the one above it, answered once per section.
     */
    public boolean fireFreeAround(int x, int y, int z) {
        return fire.freeAround(x, y, z);
    }

    /**
     * NMS state of a {@code Bukkit} block data, or {@code null} when it is not a server block data.
     */
    public Object stateOf(BlockData data) {
        return traits.stateOf(data);
    }

    /**
     * Whether it's an air.
     */
    public boolean isAir(Object state) {
        return traits.isAir(state);
    }

    /**
     * Whether it's a block entity.
     */
    public boolean hasBlockEntity(Object state) {
        return traits.hasBlockEntity(state);
    }

    /**
     * Whether it's a liquid.
     */
    public boolean liquid(Object state) {
        return traits.liquid(state);
    }

    /**
     * Whether it can be replaced.
     */
    public boolean canReplace(Object state) {
        return traits.canReplace(state);
    }

    /**
     * Whether it's a material.
     */
    public Material material(Object state) {
        return traits.material(state);
    }

    /**
     * Whether it's ageable.
     */
    public boolean ageable(Object state) {
        return traits.ageable(state);
    }

    /**
     * Whether it's waterlogged.
     */
    public boolean waterlogged(Object state) {
        return traits.waterlogged(state);
    }

    /**
     * Whether it's a string.
     */
    public String asString(Object state) {
        return traits.asString(state);
    }

    /**
     * Put {@code next} at the block when neither side is a block entity.
     *
     * @return {@code false} when the caller must use Bukkit instead
     */
    public boolean replace(int x, int y, int z, BlockData data) {
        if (capturing()) return false;
        Object next = traits.stateOf(data);
        if (next == null || traits.hasBlockEntity(next)) return false;
        Object live = read(x, y, z);
        if (live == null || traits.hasBlockEntity(live)) return false;
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
     * Write {@code next} when the caller already read {@code previous}.
     *
     * @return {@code false} when the section was not written
     */
    public boolean commit(int x, int y, int z, Object previous, Object next) {
        if (fuse.dead || next == null) return false;
        try {
            return put0(x, y, z, previous, next);
        } catch (Throwable failure) {
            fuse.trip(failure);
            return false;
        }
    }

    /**
     * Common rollback case: the block is still the expected plain state, so write the target.
     */
    public Fast placeFast(
            int x,
            int y,
            int z,
            Object target,
            Object expected,
            boolean targetAir,
            boolean expectedAir
    ) {
        if (fuse.dead || target == null) return Fast.BUKKIT;
        try {
            int targetTraits = traits.of(target);
            if ((targetTraits & StateTraits.ENTITY) != 0) return Fast.BUKKIT;
            PasteChunk chunk = chunks.get(x >> 4, z >> 4);
            if (chunk == null) return Fast.BUKKIT;
            Object section = chunk.section(y);
            if (section == null) return Fast.BUKKIT;
            Object live = ChunkHandles.GET_STATE.invokeExact(section, x & 15, y & 15, z & 15);
            lastRead = live;
            int liveTraits = traits.of(live);
            if ((liveTraits & StateTraits.ENTITY) != 0) return Fast.BUKKIT;
            boolean liveAir = (liveTraits & StateTraits.AIR) != 0;
            if (targetAir) {
                if (liveAir) return Fast.UNCHANGED;
            } else if (live == target) {
                return Fast.UNCHANGED;
            }
            if (!expectedAir && expected == null) return Fast.DRIFTED;
            if (expectedAir) {
                if (!liveAir) return Fast.DRIFTED;
            } else if (live != expected) {
                return Fast.DRIFTED;
            }
            writePlain(chunk, section, x, y, z, live, liveTraits, target, targetTraits);
            return Fast.WRITTEN;
        } catch (Throwable failure) {
            fuse.trip(failure);
            return Fast.BUKKIT;
        }
    }

    private PalettePaste(World world, Object level, Object chunkSource, Object chunkMap, Object light, Object cursor) {
        this.world = world;
        this.level = level;
        this.chunks = new ChunkCache(level, chunkMap);
        this.fire = new FireProbe(fuse, chunks, traits);
        this.notifier = new ChangeNotifier(chunkSource, cursor, dirty);
        this.relight = new RelightQueue(level, light, dirty);
        this.finisher = new PasteFinisher(dirty, notifier, relight, heightmaps);
    }

    private boolean put0(int x, int y, int z, Object previous, Object next) throws Throwable {
        PasteChunk chunk = chunks.get(x >> 4, z >> 4);
        if (chunk == null) return false;
        Object section = chunk.section(y);
        if (section == null) return false;
        Object before = previous != null ? previous : ChunkHandles.GET_STATE.invokeExact(section, x & 15, y & 15, z & 15);
        if (before == next) return true;
        int beforeTraits = traits.of(before);
        int nextTraits = traits.of(next);
        if ((beforeTraits & StateTraits.ENTITY) != 0 || (nextTraits & StateTraits.ENTITY) != 0) return false;
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
        Object placed = (Object) ChunkHandles.SET_STATE.invokeExact(section, localX, y & 15, localZ, next, true);
        if (placed != before && placed != next) {
            before = placed;
            beforeTraits = traits.of(before);
        }
        heightmaps.note(chunk, localX, y, localZ);
        if (!chunk.unsaved) {
            ChunkHandles.MARK_UNSAVED.invokeExact(chunk.nms);
            chunk.unsaved = true;
        }
        notifier.queue(chunk, localX, localZ, y);
        if (((beforeTraits | nextTraits) & StateTraits.POI) != 0) {
            Object pos = LevelHandles.NEW_POS.invokeExact(x, y, z);
            LevelHandles.UPDATE_POI.invokeExact(level, pos, before, next);
        }
        relight.note(chunk, localX, localZ, before, beforeTraits, next, nextTraits);
        written++;
    }
}
