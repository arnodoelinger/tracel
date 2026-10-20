package com.tracel.plugin.api.addon

import com.tracel.api.addon.ActorKind
import com.tracel.api.addon.BlockLens
import com.tracel.api.addon.CustomEventType
import com.tracel.api.addon.InspectLines
import com.tracel.api.addon.ItemHolderType
import com.tracel.api.addon.ItemLens
import com.tracel.api.addon.RollbackJob
import com.tracel.api.addon.RollbackOutcome
import com.tracel.api.addon.RollbackStep
import com.tracel.api.rollback.RollbackJobId
import com.tracel.api.rollback.RollbackUndone
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.model.world.block.BlockDataKey
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.rollback.RollbackSignals
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.util.log.Warnings
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

private const val MAX_CACHED_NAMES = 4_096

/** Hears rollbacks as `@OnRollbackStarted`, `@OnRollbackFinished` and `@OnUndo` want them. */
internal interface RollbackHearer {
    fun started(job: RollbackJob) {}

    fun finished(job: RollbackJob, outcome: RollbackOutcome) {}

    fun undone(undone: RollbackUndone) {}
}

/**
 * A reversible event type with its addon's guard: [tell] decodes a stored record and has the addon take it back or do
 * it again, on the addon's own lane, then says whether it went through.
 */
internal class BoundReversible(
    private val guard: AddonGuard,
    private val tellNow: (undone: Boolean, step: RollbackStep, text: String) -> Boolean,
) {
    fun tell(undone: Boolean, step: RollbackStep, text: String, done: (Boolean) -> Unit) =
        guard.later("reversible ${if (undone) "undo" else "redo"} ${step.id}") { done(tellNow(undone, step, text)) }
}

/** An addon's holder with its ID decoded, ready to read and write. */
internal class BoundHolder(
    val place: BlockPlace?,
    private val reading: () -> List<ItemStack?>?,
    private val writing: (Int, ItemStack?) -> Unit,
) {
    fun read(): List<ItemStack?>? = reading()

    fun write(slot: Int, item: ItemStack?) = writing(slot, item)
}

/**
 * Everything addons registered, for the parts of `Tracel` that ask: lookups, the inspector, rollbacks. Registering
 * is the addon context's job; this only keeps it.
 *
 * Addon code called from here is never trusted to behave: what throws is logged once and treated as not knowing.
 */
internal class Extensions(private val logger: Logger = Logger.getLogger("TracelAddons")) : RollbackSignals {
    val holders = Shelf<ItemHolderType<*>>("Holder type")
    val events = Shelf<CustomEventType<*>>("Event type")
    val reversibles = Shelf<BoundReversible>("Reversible event type")
    val actors = Shelf<ActorKind>("Actor kind")
    val lenses = Rack<BlockLens> { blockNames.clear() }
    val itemLenses = Rack<ItemLens> { itemNames.clear() }
    val rollbacks = Rack<RollbackHearer>()
    val inspectors = Rack<(BlockPlace, InspectLines) -> Unit>()

    private val blockNames = ConcurrentHashMap<String, Optional<String>>()
    private val itemNames = ConcurrentHashMap<ContentHash, Optional<String>>()
    private val runs = AtomicLong()

    /** [id]'s holder, or `null` when its addon is gone or the ID no longer decodes. */
    fun holder(id: HolderId.Custom): BoundHolder? {
        val type = holders[id.kind] ?: return null
        return guarded("holder ${id.kind}") { bind(type, id.id) }
    }

    /** How lookups name [id], as actor or as holder; `unknown <kind>` once nobody knows it. */
    fun name(id: HolderId.Custom): String {
        actors[id.kind]?.let { kind -> return guarded("actor ${id.kind}") { kind.display(id.id) } ?: id.id }
        if (id.kind in holders) return "${pretty(id.kind)} ${id.id}"
        return unknown(id.kind)
    }

    /** What a lens calls block [data], or `null` for a plain block. */
    fun blockName(data: String): String? {
        val lenses = lenses.all
        if (lenses.isEmpty()) return null
        if (blockNames.size > MAX_CACHED_NAMES) blockNames.clear()
        return blockNames.getOrPut(data) {
            val parsed = BlockDataCache.of(BlockDataKey(data))
            val named = parsed?.let { found -> lenses.firstNotNullOfOrNull { guarded("lens") { it.name(found) } } }
            Optional.ofNullable(named?.toString())
        }.orElse(null)
    }

    /** What custom block [name] is made of, as block data, or `null` when no lens knows it. */
    fun blockData(name: String): String? {
        val wanted = NamespacedName.parse(name.lowercase()) ?: return null
        return lenses.all.firstNotNullOfOrNull { lens -> guarded("lens") { lens.data(wanted) } }?.asString
    }

    /** What a lens calls items of [key], once [learnItemNames] has read it; `null` for plain or not yet read. */
    fun itemName(key: ItemKey): String? = key.decoration?.let { itemNames[it] }?.orElse(null)

    /**
     * Reads the stored forms of [keys] it has not named yet and asks the item lenses about them, so [itemName]
     * can answer without waiting. Forms come from [form]; one that is gone or does not decode names nothing.
     */
    suspend fun learnItemNames(keys: Collection<ItemKey>, form: suspend (ContentHash) -> ByteArray?) {
        val lenses = itemLenses.all
        if (lenses.isEmpty()) return
        if (itemNames.size > MAX_CACHED_NAMES) itemNames.clear()
        for (key in keys) {
            val hash = key.decoration ?: continue
            if (itemNames.containsKey(hash)) continue
            val stack = form(hash)?.let { bytes -> runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull() }
            val named = stack?.let { found -> lenses.firstNotNullOfOrNull { guarded("item lens") { it.name(found) } } }
            itemNames[hash] = Optional.ofNullable(named?.toString())
        }
    }

    /** Of [known], the keys a lens calls [name]: renamed, enchanted or not. */
    suspend fun itemKeysNamed(
        name: String,
        known: List<ItemKey>,
        form: suspend (ContentHash) -> ByteArray?,
    ): Set<ItemKey> {
        val decorated = known.filter { it.decoration != null }
        learnItemNames(decorated, form)
        return decorated.filterTo(HashSet()) { itemName(it).equals(name, ignoreCase = true) }
    }

    /** The lookup line for a custom event's stored [raw] text: what its type shows, or `unknown <name>`. */
    fun eventLine(raw: String): Component {
        val name = raw.substringBefore(' ')
        val rest = raw.substringAfter(' ', "")
        val type = events[name] ?: return Component.text(unknown(name))
        return guarded("event $name") { line(type, rest) } ?: Component.text(rest)
    }

    /** What `@OnInspect` handlers add for a click on [place]. */
    fun inspect(place: BlockPlace): List<Component> {
        val handlers = inspectors.all
        if (handlers.isEmpty()) return emptyList()
        val lines = ArrayList<Component>()
        val sink = object : InspectLines {
            override fun add(line: Component) {
                lines += line
            }
        }
        for (handler in handlers) guarded("inspect") { handler(place, sink) }
        return lines
    }

    override fun started(planned: Planned): Long {
        val run = runs.incrementAndGet()
        val hearers = rollbacks.all
        if (hearers.isEmpty()) return run
        val job = job(run, planned)
        for (hearer in hearers) guarded("rollback") { hearer.started(job) }
        return run
    }

    override fun finished(run: Long, planned: Planned, result: RollbackResult?, failure: Throwable?) {
        val hearers = rollbacks.all
        if (hearers.isEmpty()) return
        val job = job(run, planned)
        val outcome = outcome(result, failure)
        for (hearer in hearers) guarded("rollback") { hearer.finished(job, outcome) }
    }

    override fun undone(result: UndoResult) {
        val done = result as? UndoResult.Done ?: return
        val hearers = rollbacks.all
        if (hearers.isEmpty()) return
        val undone = RollbackUndone(
            RollbackJobId(done.job.raw), done.structure.count, done.structure.skipped.size,
            done.material.fullyRestored,
        )
        for (hearer in hearers) guarded("undo") { hearer.undone(undone) }
    }

    private fun job(run: Long, planned: Planned) =
        RollbackJob(run, ApiConversions.actor(planned.by, CauseKind.PLAYER_ACTION), planned.taken.size)

    private fun outcome(result: RollbackResult?, failure: Throwable?): RollbackOutcome = when (result) {
        is RollbackResult.Done -> RollbackOutcome.Done(
            RollbackJobId(result.job.raw),
            result.structure.count,
            result.structure.skipped.size,
            result.structure.overwritten,
            result.material.fullyRestored,
        )
        RollbackResult.Stale -> RollbackOutcome.Refused("history moved on while it was being planned.")
        is Unreachable -> RollbackOutcome.Refused(result.reason)
        is Blocked -> RollbackOutcome.Refused("another rollback holds some of its items.")
        null -> RollbackOutcome.Failed(failure?.message ?: failure?.javaClass?.simpleName ?: "it broke.")
    }

    private fun <I : Any> bind(type: ItemHolderType<I>, text: String): BoundHolder? {
        val id = type.decode(text) ?: return null
        return BoundHolder(type.place(id), { type.read(id) }, { slot, item -> type.write(id, slot, item) })
    }

    private fun <T : Any> line(type: CustomEventType<T>, text: String): Component? {
        val (version, payload) = EventText.split(text) ?: return null
        return type.decode(version, payload)?.let(type::line)
    }

    private inline fun <T> guarded(what: String, work: () -> T): T? = try {
        work()
    } catch (failure: Throwable) {
        Warnings.once(logger, "addon:$what") { "an addon's $what threw; treated as not knowing: $failure." }
        logger.log(Level.FINE, "addon $what threw.", failure)
        null
    }

    companion object {
        val live: Extensions = Extensions()

        fun unknown(kind: String): String = "unknown $kind"

        /** Whether [name] could only be a lens's: namespaced, and not by the game. */
        fun isCustomName(name: String): Boolean {
            val namespace = name.substringBefore(':', "")
            return namespace.isNotEmpty() && namespace != "minecraft"
        }

        private fun pretty(kind: String): String =
            kind.substringAfter(':').split('_', '-', '/').filter(String::isNotEmpty)
                .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
    }
}

/**
 * How a registered event type's record is stored after its name: `@<version> <payload>`, or `@<version>! <payload>`
 * for one a rollback takes back through its addon.
 */
internal object EventText {
    private val STORED = Regex("""@(\d+)(!?) (.*)""", RegexOption.DOT_MATCHES_ALL)

    fun join(version: Int, payload: String, reversible: Boolean = false): String =
        "@$version${if (reversible) "!" else ""} $payload"

    fun split(text: String): Pair<Int, String>? {
        val found = STORED.matchEntire(text) ?: return null
        val version = found.groupValues[1].toIntOrNull() ?: return null
        return version to found.groupValues[3]
    }

    /** Whether [text] was written by a reversible type, so a rollback must take it back outside the world. */
    fun isReversible(text: String): Boolean = STORED.matchEntire(text)?.groupValues?.get(2) == "!"
}
