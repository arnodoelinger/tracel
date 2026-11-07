package com.tracel.storage.crash

import com.tracel.storage.lsm.io.Disk
import java.io.IOException
import java.io.SyncFailedException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.random.Random

class PowerCutDisk(
    image: Image,
    private val random: Random,
    private val cutAfter: Long = Long.MAX_VALUE,
    private val faultRate: Double = 0.0,
) : Disk {
    private val names = HashMap<Path, Inode>()
    private val directories = HashSet<Path>()
    private val landed = HashMap<Path, HashMap<Path, Inode>>()
    private val pending = HashMap<Path, ArrayList<Op>>()
    private val trail = ArrayDeque<String>()

    private var image: Image? = null

    var faults = 0; private set
    var changes = 0L; private set
    var midway = false; private set

    val dead: Boolean get() = synchronized(this) { image != null }

    init {
        directories += image.directories
        for ((path, bytes) in image.files) {
            val inode = Inode(bytes.copyOf(), bytes.size, bytes.size)
            names[path] = inode
            landed.getOrPut(path.parent) { HashMap() }[path] = inode
        }
    }

    fun cut(): Image = synchronized(this) { image ?: land("cut from outside").also { image = it } }

    // region Disk

    override fun write(path: Path, fresh: Boolean): Disk.Sink = change("open ${path.fileName}") { cut ->
        val parent = path.parent
        if (parent !in directories) throw NoSuchFileException(parent.toString())
        val existing = names[path]
        if (existing != null && !fresh) return@change Sink(existing, path)
        if (cut) {
            if (random.nextBoolean()) create(path)
            throw powerCut()
        }
        fault { IOException("No space left on device") }
        Sink(create(path), path)
    }

    override fun read(path: Path): Disk.Source = synchronized(this) {
        alive()
        val inode = names[path] ?: throw NoSuchFileException(path.toString())
        Source(inode.bytes.copyOf(inode.size))
    }

    override fun map(path: Path, arena: Arena): MemorySegment {
        val bytes = synchronized(this) {
            alive()
            val inode = names[path] ?: throw NoSuchFileException(path.toString())
            inode.bytes.copyOf(inode.size)
        }
        val segment = arena.allocate(bytes.size.toLong().coerceAtLeast(1)).asSlice(0, bytes.size.toLong())
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
        return segment.asReadOnly()
    }

    override fun size(path: Path): Long = synchronized(this) {
        alive()
        names[path]?.size?.toLong() ?: -1
    }

    override fun list(directory: Path): List<Path> = synchronized(this) {
        alive()
        names.keys.filter { it.parent == directory } + directories.filter { it.parent == directory }
    }

    override fun createDirectories(directory: Path) = synchronized(this) {
        alive()
        var at: Path? = directory
        while (at != null && directories.add(at)) at = at.parent
    }

    override fun delete(path: Path) = change("delete ${path.fileName}") { cut ->
        if (path !in names) return@change
        if (cut) {
            if (random.nextBoolean()) unlink(path)
            throw powerCut()
        }
        fault { IOException("Input/output error") }
        unlink(path)
    }

    override fun replace(from: Path, to: Path) = change("rename ${from.fileName} over ${to.fileName}") { cut ->
        require(from.parent == to.parent) { "a rename across directories is not something the engine does." }
        if (from !in names) throw NoSuchFileException(from.toString())
        if (cut) {
            if (random.nextBoolean()) rename(from, to)
            throw powerCut()
        }
        fault { IOException("Input/output error") }
        rename(from, to)
    }

    override fun syncDirectory(directory: Path) = change("sync ${directory.fileName}/") { cut ->
        if (cut) {
            if (random.nextBoolean()) landDirectory(directory)
            throw powerCut()
        }
        fault { IOException("Input/output error") }
        landDirectory(directory)
    }

    // endregion

    private inner class Sink(private val inode: Inode, private val path: Path) : Disk.Sink {
        private var closed = false

        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            change("write ${path.fileName} +$length @${inode.size}") { cut ->
                if (closed) throw IOException("$path is closed.")
                if (cut) {
                    inode.append(bytes, offset, random.nextInt(length + 1))
                    throw powerCut()
                }
                if (faultRate > 0 && random.nextDouble() < faultRate) {
                    faults++
                    inode.append(bytes, offset, random.nextInt(length + 1))
                    throw IOException("No space left on device")
                }
                inode.append(bytes, offset, length)
            }

        override fun sync() = change("fsync ${path.fileName} ${inode.durable}..${inode.size}") { cut ->
            if (closed) throw IOException("$path is closed.")
            if (cut) {
                if (random.nextBoolean()) inode.durable = inode.size
                throw powerCut()
            }
            if (faultRate > 0 && random.nextDouble() < faultRate) {
                faults++
                if (random.nextBoolean()) inode.lost += inode.durable until inode.size
                throw SyncFailedException("sync failed")
            }
            inode.durable = inode.size
        }

        override fun close() {
            closed = true
        }
    }

    private class Source(private val bytes: ByteArray) : Disk.Source {
        override val size: Long get() = bytes.size.toLong()

        override fun read(buffer: ByteBuffer, at: Long): Int {
            if (at >= bytes.size) return -1
            val length = minOf(buffer.remaining(), bytes.size - at.toInt())
            buffer.put(bytes, at.toInt(), length)
            return length
        }

        override fun close() = Unit
    }

    private class Inode(var bytes: ByteArray, var size: Int, var durable: Int) {
        val lost = ArrayList<IntRange>()

        fun append(from: ByteArray, offset: Int, length: Int) {
            if (size + length > bytes.size) bytes = bytes.copyOf(maxOf(size + length, bytes.size * 2, 256))
            System.arraycopy(from, offset, bytes, size, length)
            size += length
        }
    }

    private sealed interface Op {
        fun applyTo(entries: HashMap<Path, Inode>)

        class Link(val path: Path, val inode: Inode) : Op {
            override fun applyTo(entries: HashMap<Path, Inode>) {
                entries[path] = inode
            }
        }

        class Unlink(val path: Path) : Op {
            override fun applyTo(entries: HashMap<Path, Inode>) {
                entries.remove(path)
            }
        }

        class Rename(val from: Path, val to: Path, val inode: Inode) : Op {
            override fun applyTo(entries: HashMap<Path, Inode>) {
                entries.remove(from)
                entries[to] = inode
            }
        }
    }

    enum class Loss {
        NOTHING,
        PREFIX,
        ZEROS,
        PAGES,
    }

    class Image(val files: Map<Path, ByteArray>, val directories: Set<Path>, val note: String) {
        companion object {
            val EMPTY = Image(emptyMap(), emptySet(), "a new disk")
        }
    }

    // region Internals, all under the monitor

    private inline fun <T> change(what: String, work: (cut: Boolean) -> T): T = synchronized(this) {
        alive()
        changes++
        trail.addLast(what)
        if (trail.size > TRAIL) trail.removeFirst()
        val cut = changes == cutAfter
        try {
            work(cut)
        } finally {
            if (cut && image == null) {
                midway = true
                image = land("cut during: $what")
            }
        }
    }

    private inline fun fault(error: () -> IOException) {
        if (faultRate > 0 && random.nextDouble() < faultRate) {
            faults++
            throw error()
        }
    }

    private fun alive() {
        if (image != null) throw IOException("the power is off.")
    }

    private fun powerCut() = IOException("power cut.")

    private fun create(path: Path): Inode {
        val inode = Inode(ByteArray(0), 0, 0)
        names[path] = inode
        pending.getOrPut(path.parent) { ArrayList() } += Op.Link(path, inode)
        return inode
    }

    private fun unlink(path: Path) {
        names.remove(path)
        pending.getOrPut(path.parent) { ArrayList() } += Op.Unlink(path)
    }

    private fun rename(from: Path, to: Path) {
        val inode = names.remove(from) ?: return
        names[to] = inode
        pending.getOrPut(from.parent) { ArrayList() } += Op.Rename(from, to, inode)
    }

    private fun landDirectory(directory: Path) {
        landed[directory] = names.filterTo(HashMap()) { (path, _) -> path.parent == directory }
        pending.remove(directory)
    }

    private fun land(how: String): Image {
        val loss = Loss.entries.random(random)
        val survivors = HashMap<Inode, ByteArray>()
        val files = HashMap<Path, ByteArray>()
        val notes = ArrayList<String>()
        for (directory in directories.sorted()) {
            val entries = HashMap(landed[directory].orEmpty())
            val ops = pending[directory].orEmpty()
            val kept = random.nextInt(ops.size + 1)
            if (ops.isNotEmpty()) notes += "${directory.fileName}/ kept $kept of ${ops.size} name changes"
            for (op in ops.take(kept)) op.applyTo(entries)
            for ((path, inode) in entries) {
                val bytes = survivors.getOrPut(inode) { survivor(inode, loss) }
                if (bytes.size != inode.size) notes += "${path.fileName} ${bytes.size} of ${inode.size} bytes"
                files[path] = bytes
            }
        }
        val note = "$how; $loss; ${notes.joinToString().ifEmpty { "nothing in the air" }}\n" +
                "    last changes: ${trail.joinToString(" | ")}"
        return Image(files, directories.toSet(), note)
    }

    private fun survivor(inode: Inode, loss: Loss): ByteArray {
        val durable = inode.durable
        val size = inode.size
        val out = when (loss) {
            Loss.NOTHING -> inode.bytes.copyOf(durable)
            Loss.PREFIX -> inode.bytes.copyOf(random.nextInt(durable, size + 1))
            Loss.ZEROS -> {
                val kept = random.nextInt(durable, size + 1)
                val length = random.nextInt(kept, size + 1)
                inode.bytes.copyOf(length).also { it.fill(0, kept, length) }
            }

            Loss.PAGES -> {
                val length = random.nextInt(durable, size + 1)
                val bytes = inode.bytes.copyOf(length)
                var page = durable / PAGE * PAGE
                while (page < length) {
                    if (random.nextBoolean()) bytes.fill(0, maxOf(page, durable), minOf(page + PAGE, length))
                    page += PAGE
                }
                bytes
            }
        }
        for (range in inode.lost) {
            val from = range.first.coerceAtMost(out.size)
            val until = (range.last + 1).coerceAtMost(out.size)
            out.fill(0, from, until)
        }
        return out
    }

    // endregion

    private companion object {
        const val PAGE = 4096
        const val TRAIL = 24
    }
}
