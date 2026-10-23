package com.tracel.plugin.specifics.server

import com.tracel.api.Unstable
import com.tracel.plugin.metrics.Telemetry
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Entities only one client knows of. */
@Unstable
internal object ClientEntities {
    private class Handles {
        val entity: Method = ServerClass.CRAFT_ENTITY.load().getMethod("getHandle")
        val player: Method = ServerClass.CRAFT_PLAYER.load().getMethod("getHandle")
        val connection: Field = ServerClass.SERVER_PLAYER.load().getField("connection")
        val send: Method = ServerClass.COMMON_LISTENER.load().getMethod("send", ServerClass.PACKET.load())
        val id: Method = ServerClass.ENTITY.load().getMethod("getId")
        val data: Method = ServerClass.ENTITY.load().getMethod("getEntityData")
        val packAll: Method = ServerClass.ENTITY_DATA.load().getMethod("packAll")
        val pos: Constructor<*> = ServerClass.BLOCK_POS.load()
            .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val add: Constructor<*> = ServerClass.ADD_ENTITY.load()
            .getConstructor(ServerClass.ENTITY.load(), Int::class.javaPrimitiveType, ServerClass.BLOCK_POS.load())
        val setData: Constructor<*> = ServerClass.SET_ENTITY_DATA.load()
            .getConstructor(Int::class.javaPrimitiveType, List::class.java)
        val remove: Constructor<*> = ServerClass.REMOVE_ENTITIES.load().getConstructor(IntArray::class.java)
        val bundle: Constructor<*> = ServerClass.BUNDLE.load().getConstructor(Iterable::class.java)
    }

    @Volatile
    private var broken = false
    private val handles: Handles? by lazy { runCatching { Handles() }.onFailure(::fail).getOrNull() }

    /** Whether this server can be sent such entities at all. */
    val works: Boolean get() = !broken && handles != null

    /** The ID the client knows [entity] by. */
    fun id(entity: Entity): Int = entity.entityId

    /** The packets that make [entity] appear at the block corner ([x], [y], [z]), as it is set up now. */
    fun appear(entity: Entity, x: Int, y: Int, z: Int): List<Any> = guard(emptyList()) { h ->
        val handle = h.entity.invoke(entity)
        listOf(h.add.newInstance(handle, 0, h.pos.newInstance(x, y, z)), state(h, entity, handle))
    }

    /** The packet that tells the client everything [entity] is now. */
    fun update(entity: Entity): Any? = guard(null) { h -> state(h, entity, h.entity.invoke(entity)) }

    /** The packet that takes the entities [ids] away again. */
    fun gone(ids: IntArray): Any? = guard(null) { h -> h.remove.newInstance(ids) }

    /** Sends [packets] to [player], in bundles so that the client takes each in one frame. */
    fun send(player: Player, packets: List<Any>) {
        if (packets.isEmpty()) return
        guard(Unit) { h ->
            val connection = h.connection.get(h.player.invoke(player))
            for (part in packets.chunked(BUNDLE_MOST)) h.send.invoke(connection, h.bundle.newInstance(part))
        }
    }

    private fun state(h: Handles, entity: Entity, handle: Any): Any =
        h.setData.newInstance(entity.entityId, h.packAll.invoke(h.data.invoke(handle)))

    private inline fun <T> guard(otherwise: T, body: (Handles) -> T): T {
        val h = handles ?: return otherwise
        if (broken) return otherwise
        return try {
            body(h)
        } catch (failure: ReflectiveOperationException) {
            fail(failure)
            otherwise
        }
    }

    private fun fail(failure: Throwable) {
        if (broken) return
        broken = true
        Telemetry.error(failure)
    }

    private const val BUNDLE_MOST = 4_000
}
