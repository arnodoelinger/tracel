package com.tracel.plugin.importer.coreprotect

import com.tracel.plugin.util.AIR
import com.tracel.model.transaction.CauseKind
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.event.EventKind
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityExtras
import com.tracel.storage.TracelStorage
import com.tracel.storage.ports.actor.ActorFacts
import com.tracel.storage.ports.event.EventLog
import com.tracel.storage.ports.log.TransactionLog
import com.tracel.storage.ports.log.WorldLog
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.ports.ops.ForeignHistory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.*

class CoreProtectImportTest {
    private val overworld = WorldId(UUID(0L, 1L))
    private val steve = UUID(0L, 42L)

    private val platform = object : ImportPlatform {
        override fun world(name: String): WorldId? = overworld.takeIf { name == "world" }

        override fun blockState(state: String): String? = when {
            state.startsWith("minecraft:grass[") || state == "minecraft:grass" -> null
            state == "minecraft:water" -> "minecraft:water[level=0]"
            else -> state
        }

        override fun entityType(name: String): String? =
            name.takeIf { it in setOf("pig", "creeper", "tnt", "zombie") }?.let { "minecraft:$it" }

        override fun player(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

        override fun decode(blob: ByteArray): List<Any?> = blob.toString(Charsets.UTF_8).split('|')

        override fun blockExtras(state: String, detail: BlockDetail): ByteArray = when (detail) {
            is BlockDetail.Sign -> "sign:${detail.lines.joinToString("/")}:${detail.glowing}:${detail.waxed}"
            is BlockDetail.Command -> "command:${detail.command}"
            is BlockDetail.Head -> "head:${detail.owner}"
            is BlockDetail.Spawner -> "spawner:${detail.entity}"
            is BlockDetail.Banner -> "banner"
        }.toByteArray()


        override fun itemKey(material: String, metadata: List<Any?>?): ItemKey? {
            if (material == "minecraft:removed_item") return null
            return ItemKey(
                material.substringAfter(':').uppercase(),
                metadata?.let { com.tracel.model.item.ContentHash(it.joinToString()) })
        }

        override fun stack(entry: Any?): Pair<ItemKey, Int>? =
            (entry as? String)?.split('x')?.takeIf { it.size == 2 }?.let { ItemKey(it[1]) to it[0].toInt() }

        override fun entitySnapshot(
            world: WorldId,
            type: String,
            x: Double,
            y: Double,
            z: Double,
            kept: List<Any?>
        ): ByteArray =
            "$type:${kept.joinToString("|")}".toByteArray()
    }

    private class Store(dir: Path) : AutoCloseable {
        val storage: TracelStorage = TracelStorage.open(dir)
        val counters = Counters(storage)
        val log = WorldLog(storage)
        val transactions = TransactionLog(storage)
        val events = EventLog(storage)
        val foreign = ForeignHistory(storage, log, transactions, events, counters)
        override fun close() = storage.close()
    }

    private fun Store.importer(platform: ImportPlatform) = CoreProtectImport(foreign, platform) { it() }

    private suspend fun Store.import(location: CoreProtectLocation, stop: () -> Boolean = { false }) =
        CoreProtectDatabase.open(location).use { importer(platform).run(it, stop) { _, _ -> } }

    private fun database(dir: Path, fill: (insert: (String) -> Unit) -> Unit): CoreProtectLocation.File {
        val file = Files.createDirectories(dir).resolve("database.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for (table in SCHEMA) statement.execute(table)
                statement.execute("INSERT INTO co_version VALUES (1700000000, '2.24.0')")
                statement.execute("INSERT INTO co_world VALUES (1, 'world'), (2, 'world_gone')")
                statement.execute(
                    "INSERT INTO co_user (id, time, user, uuid) VALUES (1, 1700000000, 'Steve', '$steve'), " +
                            "(2, 1700000000, '#tnt', NULL), (3, 1700000000, '#lava', NULL), (4, 1700000000, 'OldTimer', NULL), " +
                            "(5, 1700000000, '#hopper', NULL)",
                )
                statement.execute(
                    "INSERT INTO co_material_map VALUES (1, 'minecraft:stone'), (2, 'minecraft:oak_stairs'), " +
                            "(3, 'minecraft:grass'), (4, 'dirt'), (5, 'minecraft:lava'), (6, 'minecraft:oak_sign'), " +
                            "(7, 'minecraft:diamond'), (8, 'minecraft:command_block'), (9, 'minecraft:player_head'), " +
                            "(10, 'minecraft:spawner'), (11, 'minecraft:lever'), (12, 'minecraft:removed_item'), " +
                            "(13, 'minecraft:red_shulker_box')",
                )
                statement.execute("INSERT INTO co_blockdata_map VALUES (1, 'facing=north'), (2, 'waterlogged=true'), (3, 'half=bottom')")
                statement.execute("INSERT INTO co_entity_map VALUES (1, 'pig'), (2, 'herobrine'), (3, 'zombie')")
                statement.execute("INSERT INTO co_entity (id, time, data) VALUES (1, 1700000000, CAST('baby|tame|Dolly' AS BLOB))")
                statement.execute("INSERT INTO co_skull (id, time, owner, skin) VALUES (1, 1700000000, 'Notch', NULL)")
                fill { statement.execute(it) }
            }
        }
        return CoreProtectLocation.File(file)
    }

    private fun block(
        time: Long, user: Int, x: Int, type: Int, action: Int,
        blockData: String? = null, world: Int = 1, rolledBack: Int = 0, data: Int = 0, meta: String? = null,
    ) =
        "INSERT INTO co_block VALUES ($time, $user, $world, $x, 64, 0, $type, $data, ${blob(meta)}, ${blob(blockData)}, $action, $rolledBack)"

    private fun sign(time: Long, x: Int, action: Int, front: String, glow: Int = 0) =
        "INSERT INTO co_sign VALUES ($time, 1, 1, $x, 64, 0, $action, 0, 0, $glow, 0, 0, '$front', '', '', '', NULL, NULL, NULL, NULL)"

    private fun container(
        time: Long,
        user: Int,
        x: Int,
        type: Int,
        amount: Int,
        action: Int,
        metadata: String? = null
    ) =
        "INSERT INTO co_container VALUES ($time, $user, 1, $x, 64, 0, $type, 0, $amount, ${blob(metadata)}, $action, 0)"

    private fun item(time: Long, type: Int, amount: Int, action: Int) =
        "INSERT INTO co_item VALUES ($time, 1, 1, 0, 64, 0, $type, NULL, $amount, $action, 0)"

    private fun blob(text: String?) = text?.let { "CAST('$it' AS BLOB)" } ?: "NULL"

    private suspend fun Store.at(x: Int): List<WorldChange> = log.at(BlockPos(overworld, x, 64, 0), 10)

    private fun WorldChange.block() = subject as ChangeSubject.Block

    private fun BlockShape.detail() = (extras as? BlockExtras.Opaque)?.bytes?.toString(Charsets.UTF_8)

    @Test
    fun `rows become changes with both sides, and nothing is taken twice`(@TempDir dir: Path) = runTest {
        val location = database(dir.resolve("cp")) { insert ->
            insert(block(1_000, 1, x = 1, type = 1, action = 0))
            insert(block(1_001, 1, x = 1, type = 2, action = 1, blockData = "1,2,3"))
            insert(block(1_002, 1, x = 1, type = 2, action = 0, blockData = "1,2,3"))
            insert(block(1_003, 4, x = 2, type = 4, action = 1))
            insert(block(1_004, 1, x = 3, type = 11, action = 2))
            insert(block(1_005, 1, x = 4, type = 3, action = 0))
            insert(block(1_006, 1, x = 5, type = 1, action = 0, world = 2))
            insert(block(1_007, 1, x = 6, type = 1, action = 3))
            insert(block(1_008, 1, x = 7, type = 2, action = 3))
        }
        Store(dir.resolve("tracel")).use { store ->
            val tally = store.import(location).tally
            assertEquals(mapOf(Taken.BLOCKS to 4L, Taken.CLICKS to 1L, Taken.ENTITIES to 1L), tally.taken.toMap())
            assertEquals(mapOf(Skipped.BLOCK to 1L, Skipped.WORLD to 1L, Skipped.ENTITY to 1L), tally.skipped.toMap())
            assertEquals(setOf("world_gone"), tally.missingWorlds)

            val stairs = "minecraft:oak_stairs[facing=north,waterlogged=true,half=bottom]"
            val history = store.at(1)
            assertEquals(listOf(1_002_000L, 1_001_000L, 1_000_000L), history.map { it.epochMillis })
            assertEquals(
                BlockShape(BlockDataKey("minecraft:water[level=0]")),
                history[0].block().after,
                "a waterlogged block leaves its water"
            )
            assertEquals(stairs, history[0].block().before.data.value)
            assertEquals(AIR, history[1].block().before, "placed where the row before said nothing was left")
            assertEquals(stairs, history[1].block().after.data.value)
            assertEquals(ActionKind.BLOCK_BREAK, history[2].action)
            assertEquals(HolderId.Player(steve), history[2].causedBy)

            val dirt = store.at(2).single()
            assertEquals("minecraft:dirt", dirt.block().after.data.value, "an old bare name gets its namespace")
            assertEquals(HolderId.Player(platform.player("OldTimer")), dirt.causedBy)

            val click = store.at(3).single()
            assertEquals(ActionKind.BLOCK_CLICK, click.action)
            assertEquals(click.block().before, click.block().after, "a click leaves the block as it was")

            val pig = store.at(6).single()
            assertEquals(ActionKind.ENTITY_REMOVE, pig.action)
            assertEquals("minecraft:pig", (pig.subject as ChangeSubject.Entity).type.value)

            assertEquals(0, store.import(location).tally.rows, "the marks are past every row")
            assertEquals(3, store.at(1).size)
        }
    }

    @Test
    fun `a sign gets its text from the other table, and keeps it when it is broken`(@TempDir dir: Path) = runTest {
        val location = database(dir.resolve("cp")) { insert ->
            insert(block(1_000, 1, x = 1, type = 6, action = 1))
            insert(sign(1_000, x = 1, action = 1, front = "hello"))
            insert(sign(1_009, x = 1, action = 3, front = "hello"))
            insert(block(1_010, 1, x = 1, type = 6, action = 1))
            insert(sign(1_010, x = 1, action = 1, front = "bye", glow = 1))
            insert(block(1_020, 1, x = 1, type = 6, action = 0))
            insert(sign(1_015, x = 1, action = 0, front = "bye", glow = 1))
            insert(sign(1_030, x = 9, action = 1, front = "a sign nobody saw placed"))
        }
        Store(dir.resolve("tracel")).use { store ->
            val tally = store.import(location).tally
            assertEquals(mapOf(Taken.BLOCKS to 2L, Taken.SIGNS to 2L), tally.taken.toMap())
            assertEquals(3, tally.folded)
            assertEquals(1L, tally.skipped[Skipped.UNREADABLE])

            val history = store.at(1)
            assertEquals(
                listOf(ActionKind.BLOCK_BREAK, ActionKind.SIGN_EDIT, ActionKind.SIGN_EDIT, ActionKind.BLOCK_PLACE),
                history.map { it.action },
            )
            assertEquals(
                "sign:bye///////:true:false",
                history[0].block().before.detail(),
                "a rollback puts the text back with the sign"
            )
            assertEquals("sign:hello///////:false:false", history[1].block().before.detail())
            assertEquals("sign:bye///////:true:false", history[1].block().after.detail())
            assertNull(history[2].block().before.detail())
            assertEquals("sign:hello///////:false:false", history[2].block().after.detail())
        }
    }

    @Test
    fun `what a block entity carried comes along`(@TempDir dir: Path) = runTest {
        val location = database(dir.resolve("cp")) { insert ->
            insert(block(1_000, 1, x = 1, type = 8, action = 1, meta = "say hi"))
            insert(block(1_001, 1, x = 2, type = 9, action = 1, data = 1))
            insert(block(1_002, 1, x = 3, type = 10, action = 0, data = 3))
        }
        Store(dir.resolve("tracel")).use { store ->
            store.import(location)
            assertEquals("command:say hi", store.at(1).single().block().after.detail())
            assertEquals("head:Notch", store.at(2).single().block().after.detail())
            assertEquals("spawner:zombie", store.at(3).single().block().before.detail())
        }
    }

    @Test
    fun `a shulker box brings what was in it, and a kill what the mob was`(@TempDir dir: Path) = runTest {
        val location = database(dir.resolve("cp")) { insert ->
            insert(block(1_000, 1, x = 1, type = 13, action = 1, meta = "3xDIAMOND|2xDIRT|5xDIAMOND"))
            insert(block(1_010, 2, x = 1, type = 13, action = 0, meta = "8xDIAMOND"))
            insert(block(1_020, 1, x = 2, type = 13, action = 1))
            insert(block(1_030, 1, x = 3, type = 1, action = 3, data = 1))
            insert(block(1_031, 1, x = 4, type = 1, action = 3, data = 99))
            insert(block(1_040, 2, x = 5, type = 0, action = 3, data = 1))
        }
        Store(dir.resolve("tracel")).use { store ->
            val tally = store.import(location).tally
            assertEquals(mapOf(Taken.BLOCKS to 3L, Taken.ENTITIES to 2L, Taken.DEATHS to 1L), tally.taken.toMap())
            val death = store.events.query(LookupFilter(), setOf(EventKind.DEATH)).single()
            assertEquals(
                HolderId.Player(steve) to "tnt",
                death.by to death.text,
                "a player killed is a row of its own kind"
            )

            val box = HolderId.Block(overworld, 1, 64, 0)
            val (blown, placed) = store.transactions.query(LookupFilter(holders = setOf(box)))
            assertEquals(
                mapOf("DIAMOND" to 8L, "DIRT" to 2L),
                placed.flows.associate { it.itemKey.material to it.quantity.raw })
            assertTrue(
                placed.flows.all { it.source == HolderId.Player(steve) && it.destination == box },
                "put down with these in it"
            )
            assertEquals(FlowKind.BURN, blown.flows.single().kind, "nobody took them: the blast did")
            assertEquals(box, blown.flows.single().source)
            assertTrue(
                store.transactions.query(LookupFilter(holders = setOf(HolderId.Block(overworld, 2, 64, 0)))).isEmpty(),
                "an empty box moved nothing"
            )

            val pig = store.at(3).single().subject as ChangeSubject.Entity
            assertEquals(
                "minecraft:pig:baby|tame|Dolly",
                (pig.before!!.extras as EntityExtras.Opaque).bytes.toString(Charsets.UTF_8)
            )
            assertNull(
                (store.at(4).single().subject as ChangeSubject.Entity).before!!.extras,
                "nothing kept of it, so only what it was"
            )
        }
    }

    @Test
    fun `items, chat, commands and sessions go to the logs that keep them`(@TempDir dir: Path) = runTest {
        val location = database(dir.resolve("cp")) { insert ->
            insert(container(1_000, 1, x = 1, type = 7, amount = 5, action = 1))
            insert(container(1_001, 1, x = 1, type = 7, amount = 2, action = 0, metadata = "sharp"))
            insert(container(1_002, 5, x = 1, type = 7, amount = 1, action = 1))
            insert(container(1_003, 1, x = 1, type = 12, amount = 1, action = 1))
            insert(item(1_010, type = 7, amount = 3, action = 2))
            insert(item(1_011, type = 7, amount = 3, action = 3))
            insert(item(1_012, type = 7, amount = 1, action = 10))
            insert(item(1_013, type = 7, amount = 0, action = 2))
            insert("INSERT INTO co_session VALUES (900, 1, 1, 0, 64, 0, 1)")
            insert("INSERT INTO co_session VALUES (2000, 1, 1, 0, 64, 0, 0)")
            insert("INSERT INTO co_chat VALUES (1500, 1, 2, 0, 64, 0, 'hi there')")
            insert("INSERT INTO co_command VALUES (1501, 1, 1, 0, 64, 0, '/home')")
            insert("INSERT INTO co_command VALUES (1502, 5, 1, 0, 64, 0, '/not a player')")
        }
        Store(dir.resolve("tracel")).use { store ->
            val tally = store.import(location).tally
            assertEquals(
                mapOf(
                    Taken.CONTAINERS to 3L,
                    Taken.ITEMS to 3L,
                    Taken.SESSIONS to 2L,
                    Taken.COMMANDS to 1L,
                    Taken.CHAT to 1L
                ),
                tally.taken.toMap(),
            )
            assertEquals(mapOf(Skipped.ITEM to 1L, Skipped.UNREADABLE to 2L), tally.skipped.toMap())

            val player = HolderId.Player(steve)
            val chest = HolderId.Block(overworld, 1, 64, 0)
            val moved = store.transactions.query(LookupFilter(holders = setOf(chest)))
            assertEquals(3, moved.size)
            val (hopper, took, put) = moved
            assertEquals(player to chest, put.flows.single().let { it.source to it.destination })
            assertEquals(5L, put.flows.single().quantity.raw)
            assertEquals(chest to player, took.flows.single().let { it.source to it.destination })
            assertEquals(
                "sharp",
                took.flows.single().itemKey.decoration?.hex,
                "what the item carried is part of what it is"
            )
            assertEquals(CauseKind.MACHINE, hopper.cause)
            assertEquals(FlowKind.MINT, hopper.flows.single().kind)
            assertTrue(
                moved.all { store.transactions.lotsAt(it.seq).isEmpty() },
                "no lots, so nothing a rollback can move"
            )

            val handled = store.transactions.query(LookupFilter(holders = setOf(player), since = 1_010_000))
            assertEquals(
                listOf(FlowKind.TRANSFORM_OUT, FlowKind.MOVE, FlowKind.MOVE),
                handled.map { it.flows.single().kind })
            assertEquals(CauseKind.CRAFT, handled[0].cause)
            assertTrue(handled[1].flows.single().source is HolderId.ItemEntity, "picked up")
            assertTrue(handled[2].flows.single().destination is HolderId.ItemEntity, "dropped")

            val events = store.events.query(LookupFilter(holders = setOf(player)), EventKind.entries.toSet())
            assertEquals(
                listOf(EventKind.QUIT, EventKind.COMMAND, EventKind.CHAT, EventKind.JOIN),
                events.map { it.kind })
            assertEquals("/home", events[1].text)
            assertEquals("hi there", events[2].text)
            assertNull(events[2].at, "said in a world that is gone: the words stay, the place does not")
            assertEquals(BlockPos(overworld, 0, 64, 0), events[1].at)
        }
    }

    @Test
    fun `a blast is one event, and its doer is the mob it names`(@TempDir dir: Path) = runTest {
        val location = database(dir.resolve("cp")) { insert ->
            for (x in 0 until 40) insert(block(2_000, 2, x = x, type = 1, action = 0))
            insert(block(2_000, 3, x = 50, type = 5, action = 1))
        }
        Store(dir.resolve("tracel")).use { store ->
            store.import(location)
            val blown = store.at(17).single()
            assertEquals(CauseKind.EXPLOSION, blown.cause)
            val tnt = blown.causedBy as HolderId.Entity
            assertEquals("minecraft:tnt", ActorFacts(store.storage).kindsOf(listOf(tnt.uuid))[tnt.uuid]?.value)

            val lava = store.at(50).single()
            assertEquals(CauseKind.WORLD, lava.cause)
            assertNull(lava.causedBy)
        }
    }

    @Test
    fun `what happened after our own history began is left to us`(@TempDir dir: Path) = runTest {
        val location = database(dir.resolve("cp")) { insert ->
            insert(block(1_000, 1, x = 1, type = 1, action = 0))
            insert(block(9_000, 1, x = 1, type = 1, action = 1))
            insert("INSERT INTO co_chat VALUES (9001, 1, 1, 0, 64, 0, 'too late')")
        }
        Store(dir.resolve("tracel")).use { store ->
            store.log.append(
                WorldChange(
                    store.counters.nextSeq(),
                    ActionKind.BLOCK_PLACE,
                    CauseKind.PLAYER_ACTION,
                    HolderId.Player(steve),
                    5_000_000,
                    BlockPos(overworld, 1, 64, 0),
                    ChangeSubject.Block(AIR, BlockShape(BlockDataKey("minecraft:stone"))),
                ),
            )
            val tally = store.import(location).tally
            assertEquals(mapOf(Taken.BLOCKS to 1L), tally.taken.toMap())
            assertEquals(2L, tally.skipped[Skipped.OVERLAP])
            assertEquals(listOf(5_000_000L, 1_000_000L), store.at(1).map { it.epochMillis })
        }
    }

    @Test
    fun `a stopped import carries on from the rows it stopped at, table by table`(@TempDir dir: Path) = runTest {
        val location = database(dir.resolve("cp")) { insert ->
            for (i in 0 until 9_000) insert(block(1_000L + i, 1, x = i, type = 1, action = 0))
            for (i in 0 until 3_000) insert("INSERT INTO co_chat VALUES (${1_000L + i * 3}, 1, 1, 0, 64, 0, 'line $i')")
        }
        var first = 0L
        Store(dir.resolve("tracel")).use { store ->
            var batches = 0
            val outcome = store.import(location) { batches++ >= 1 }
            assertTrue(outcome.stopped)
            first = outcome.tally.taken.values.sum()
            assertTrue(first in 1 until 12_000)
        }
        Store(dir.resolve("tracel")).use { store ->
            val second = store.import(location)
            assertFalse(second.stopped)
            assertEquals(12_000, first + second.tally.taken.values.sum())
            assertEquals(1, store.at(0).size)
            assertEquals(1, store.at(8_999).size)
            val chat = store.events.query(LookupFilter(limit = Int.MAX_VALUE), setOf(EventKind.CHAT))
            assertEquals(3_000, chat.size)
            assertEquals(3_000, chat.map { it.text }.distinct().size)
            assertEquals(chat.sortedByDescending { it.epochMillis }, chat, "read out in the order it was said")
        }
    }

    @Test
    fun `the developer server's own database goes in`(@TempDir dir: Path) = runTest {
        val real = Path.of("run/plugins/CoreProtect/database.db")
        assumeTrue(Files.isRegularFile(real))
        val lenient = object : ImportPlatform by platform {
            override fun world(name: String): WorldId = WorldId(UUID.nameUUIDFromBytes(name.toByteArray()))
            override fun blockState(state: String): String =
                if (state == "minecraft:water") "minecraft:water[level=0]" else state

            override fun entityType(name: String): String = "minecraft:${name.substringAfter(':')}"
            override fun decode(blob: ByteArray): List<Any?>? = null
        }
        Store(dir).use { store ->
            val outcome = CoreProtectDatabase.open(CoreProtectLocation.File(real))
                .use { store.importer(lenient).run(it, { false }) { _, _ -> } }
            val tally = outcome.tally
            println("CoreProtect ${outcome.outlook.version}: ${tally.rows} rows -> ${tally.taken}, skipped ${tally.skipped}, folded ${tally.folded}, ${outcome.tookMillis} ms")
            assertEquals(outcome.outlook.lastRows.sum(), tally.rows)
            assertEquals(tally.rows, tally.taken.values.sum() + tally.skipped.values.sum() + tally.folded)
            assertTrue(tally.taken.getValue(Taken.BLOCKS) > 0)
        }
    }

    private companion object {
        val SCHEMA = listOf(
            "CREATE TABLE co_block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, " +
                    "data INTEGER, meta BLOB, blockdata BLOB, action INTEGER, rolled_back INTEGER)",
            "CREATE TABLE co_chat (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)",
            "CREATE TABLE co_command (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)",
            "CREATE TABLE co_container (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, " +
                    "data INTEGER, amount INTEGER, metadata BLOB, action INTEGER, rolled_back INTEGER)",
            "CREATE TABLE co_item (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, " +
                    "data BLOB, amount INTEGER, action INTEGER, rolled_back INTEGER)",
            "CREATE TABLE co_session (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER)",
            "CREATE TABLE co_sign (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER, " +
                    "color INTEGER, color_secondary INTEGER, data INTEGER, waxed INTEGER, face INTEGER, line_1 TEXT, line_2 TEXT, " +
                    "line_3 TEXT, line_4 TEXT, line_5 TEXT, line_6 TEXT, line_7 TEXT, line_8 TEXT)",
            "CREATE TABLE co_entity (id INTEGER PRIMARY KEY ASC, time INTEGER, data BLOB)",
            "CREATE TABLE co_skull (id INTEGER PRIMARY KEY ASC, time INTEGER, owner TEXT, skin TEXT)",
            "CREATE TABLE co_user (id INTEGER PRIMARY KEY ASC, time INTEGER, user TEXT, uuid TEXT)",
            "CREATE TABLE co_world (id INTEGER, world TEXT)",
            "CREATE TABLE co_material_map (id INTEGER, material TEXT)",
            "CREATE TABLE co_blockdata_map (id INTEGER, data TEXT)",
            "CREATE TABLE co_entity_map (id INTEGER, entity TEXT)",
            "CREATE TABLE co_version (time INTEGER, version TEXT)",
        )
    }
}
