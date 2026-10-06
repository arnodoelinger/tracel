package com.tracel.storage.format

import com.tracel.engine.store.StoreFormatException
import com.tracel.platform.Versions
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.records.recordBytes
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.spi.MutationBatch
import java.lang.foreign.MemorySegment

/**
 * The layout of what is in the database: records, key families, interning.
 *
 * [major] is a different database altogether and is not expected to ever change; [minor] changes now and then, and
 * every change has a [Migration] that takes the one before it to this one.
 */
data class FormatVersion(val major: Int, val minor: Int) : Comparable<FormatVersion> {
    override fun compareTo(other: FormatVersion): Int =
        compareValuesBy(this, other, FormatVersion::major, FormatVersion::minor)

    override fun toString(): String = "$major.$minor"
}

/** One step from [from] to the next minor version, [to]. */
class Migration(
    val from: FormatVersion,
    val to: FormatVersion,
    val run: (StorageUnit) -> Unit,
) {
    init {
        require(from.major == to.major && to.minor == from.minor + 1) {
            "a migration goes from $from to the next minor version, not to $to"
        }
    }
}


/** What [StoreFormat.ensure] did: [from] is [to] when nothing had to move. */
class FormatOutcome(val from: FormatVersion, val to: FormatVersion) {
    val migrated: Boolean get() = from != to
}

/**
 * Keeps the database and the build that opens it in agreement about the format.
 *
 * Before anything else touches the database: one written by an older minor version is migrated up, silently, one step
 * at a time, each step landing together with the new version in one commit, so a crash leaves it at the last step that
 * finished. One written by a newer minor version, or by another major, is refused: a build that does not know a format
 * would read it wrongly, or worse, write it wrongly.
 */
object StoreFormat {
    /** The format this build reads and writes. */
    val CURRENT = FormatVersion(Versions.Format.DB_MAJOR, Versions.Format.DB_MINOR)

    /**
     * What a database that has no version recorded was written in: everything before versions were kept.
     *
     * Never changes.
     */
    val LEGACY = FormatVersion(1, 0)

    /** Every step there is, in no particular order. Add one for each bump of [CURRENT]'s minor. */
    val MIGRATIONS: List<Migration> = emptyList()

    /**
     * Brings [storage] to [current], or throws [StoreFormatException] if that cannot be done.
     * Meant for start-up, before the store has any other user.
     */
    fun ensure(
        storage: TracelStorage,
        current: FormatVersion = CURRENT,
        migrations: List<Migration> = MIGRATIONS,
    ): FormatOutcome {
        val engine = storage.engine
        val (stored, empty) = StorageUnit(engine.snapshot(), MutationBatch()).use { unit ->
            unit.get(Keys.formatVersion())?.let(::decode) to !unit.scan(ByteArray(0)).use { it.next() }
        }

        if (stored == null) {
            val start = if (empty) current else LEGACY
            commit(storage, start) { }
            return climb(storage, start, current, migrations)
        }
        if (stored.major != current.major) {
            throw StoreFormatException(
                "This database is format $stored, and this Tracel reads format ${current.major}.x. " +
                        "Export it with the Tracel that wrote it, and import the export here."
            )
        }
        if (stored > current) {
            throw StoreFormatException(
                "This database was written by a newer Tracel (format $stored); this one reads up to $current. " +
                        "Update Tracel: going back to an older version could damage the history."
            )
        }
        return climb(storage, stored, current, migrations)
    }

    private fun climb(
        storage: TracelStorage,
        start: FormatVersion,
        current: FormatVersion,
        migrations: List<Migration>,
    ): FormatOutcome {
        var at = start
        while (at < current) {
            val step = migrations.firstOrNull { it.from == at }
                ?: throw StoreFormatException("This Tracel has no way to bring a format $at database up to $current.")
            commit(storage, step.to, step.run)
            at = step.to
        }
        if (at != start) storage.reloadInterning()
        return FormatOutcome(start, at)
    }

    private fun commit(storage: TracelStorage, version: FormatVersion, step: (StorageUnit) -> Unit) {
        val engine = storage.engine
        StorageUnit(engine.snapshot(), MutationBatch()).use { unit ->
            step(unit)
            unit.put(Keys.formatVersion(), encode(version))
            engine.write(unit.batch, durable = true)
        }
    }

    private fun encode(version: FormatVersion): ByteArray = recordBytes(8) {
        putI32(0, version.major)
        putI32(4, version.minor)
    }

    private fun decode(value: MemorySegment): FormatVersion = FormatVersion(value.i32(0), value.i32(4))
}
