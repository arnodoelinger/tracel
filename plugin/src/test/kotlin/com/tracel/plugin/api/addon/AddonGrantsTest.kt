package com.tracel.plugin.api.addon

import com.tracel.api.access.AccessError
import com.tracel.api.access.Grant
import com.tracel.api.access.TracelConnection
import com.tracel.api.addon.Addon
import com.tracel.api.addon.AddonContext
import com.tracel.api.addon.CustomEventType
import com.tracel.api.addon.Reversible
import com.tracel.api.addon.RollbackStep
import com.tracel.api.addon.TracelAddon
import com.tracel.api.result.Res
import com.tracel.api.result.getOrNull
import com.tracel.api.world.NamespacedName
import com.tracel.api.write.WriteError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Logger

class AddonGrantsTest {
    private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }

    object Paid : CustomEventType<String> {
        override val name = NamespacedName("eco", "paid")
        override val version = 1
        override fun encode(value: String) = value
        override fun decode(version: Int, text: String) = text
        override fun line(value: String): Component = Component.text(value)
    }

    object Refunds : Reversible<String> {
        override fun undo(step: RollbackStep, value: String) = Unit
        override fun redo(step: RollbackStep, value: String) = Unit
    }

    @Addon(id = "eco")
    class Eco : TracelAddon {
        lateinit var context: AddonContext

        override fun onEnable(context: AddonContext) {
            this.context = context
        }
    }

    private fun enabled(grants: Set<Grant>): Pair<Eco, TracelConnection> {
        val connection = DetachedConnection("Eco", grants)
        val addon = Eco()
        AddonManager(
            logger,
            isPluginEnabled = { true },
            extensions = Extensions(logger),
            guards = { id, trip -> AddonGuard(id, logger, trip, lane = Dispatchers.Unconfined) },
            connect = { connection },
        ).apply {
            register(addon)
            start()
        }
        return addon to connection
    }

    @Test
    fun `an addon only gets what the admin granted its plugin`() {
        val (eco) = enabled(setOf(Grant.ROLLBACK))
        assertEquals(Res.Err(AccessError.NotGranted(Grant.ATTRIBUTE)), eco.context.recorder)
        assertTrue(eco.context.reversibles is Res.Ok)

        val (bare) = enabled(emptySet())
        assertEquals(Res.Err(AccessError.NotGranted(Grant.ROLLBACK)), bare.context.reversibles)
    }

    @Test
    fun `an event recorded without the attribute grant is refused, not written`() = runTest {
        val (eco) = enabled(setOf(Grant.ROLLBACK))
        val paid = eco.context.reversibles.getOrNull()!!.register(Paid, Refunds)
        assertEquals(Res.Err(WriteError.NotGranted(Grant.ATTRIBUTE)), paid.record("alice bob 100"))
    }

    @Test
    fun `a closed connection cuts the addon off`() {
        val (eco, connection) = enabled(Grant.entries.toSet())
        connection.close()
        assertFalse(connection.isOpen)
        assertEquals(Res.Err(AccessError.Revoked), eco.context.reversibles)
        assertEquals(Res.Err(AccessError.Revoked), eco.context.recorder)
    }
}
