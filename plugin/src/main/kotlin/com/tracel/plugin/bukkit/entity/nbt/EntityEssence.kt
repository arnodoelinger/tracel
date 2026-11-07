package com.tracel.plugin.bukkit.entity.nbt

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What of a mob's NBT is worth keeping. */
internal object EntityEssence {
    private val VOLATILE = setOf(
        "Pos", "Rotation", "Motion", "UUID", "WorldUUIDMost", "WorldUUIDLeast",
        "Paper.Origin", "Paper.OriginWorld", "Spigot.ticksLived", "Bukkit.Aware", "Bukkit.updateLevel",
        "Brain", "last_hurt_by_mob", "last_hurt_by_player", "ticks_since_last_hurt_by_mob", "HurtTime", "HurtByTimestamp",
        "DeathTime", "Fire", "Air", "fall_distance", "OnGround", "PortalCooldown", "FallFlying", "InWaterTime",
        "FreezingTime", "TicksFrozen", "current_impulse_context_reset_grace_time", "AbsorptionAmount",
    )

    private const val SPAWN_DICE = "minecraft:random_spawn_bonus"
    private const val REINFORCEMENT_DICE = "minecraft:spawn_reinforcements"

    fun slim(gzipped: ByteArray, defaultOf: (String) -> Double?): ByteArray? {
        val root = Nbt.read(gunzip(gzipped) ?: return null) ?: return null
        val kept = ArrayList<NbtField>(root.fields.size)
        for (field in root.fields) {
            if (field.name in VOLATILE) continue
            if (field.name == "attributes" && field.type == NbtType.LIST) {
                val trimmed = trimAttributes(field.payload, defaultOf) ?: continue
                kept += NbtField(field.type, field.name, trimmed)
                continue
            }
            kept += field
        }
        return gzip(Nbt.write(NbtCompound(root.rootName, kept)))
    }

    fun inflate(gzipped: ByteArray, uuid: UUID?, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): ByteArray {
        val root = Nbt.read(gunzip(gzipped) ?: return gzipped) ?: return gzipped
        val missing = ArrayList<NbtField>(4)
        if (uuid != null && root["UUID"] == null) {
            missing += NbtField(
                NbtType.INTS, "UUID",
                Nbt.ints(
                    (uuid.mostSignificantBits ushr 32).toInt(), uuid.mostSignificantBits.toInt(),
                    (uuid.leastSignificantBits ushr 32).toInt(), uuid.leastSignificantBits.toInt(),
                ),
            )
        }
        if (root["Pos"] == null) missing += NbtField(NbtType.LIST, "Pos", Nbt.doubles(x, y, z))
        if (root["Rotation"] == null) missing += NbtField(NbtType.LIST, "Rotation", Nbt.floats(yaw, pitch))
        if (root["Motion"] == null) missing += NbtField(NbtType.LIST, "Motion", Nbt.doubles(0.0, 0.0, 0.0))
        if (missing.isEmpty()) return gzipped
        return gzip(Nbt.write(NbtCompound(root.rootName, root.fields + missing)))
    }

    private fun trimAttributes(payload: ByteArray, defaultOf: (String) -> Double?): ByteArray? {
        val entries = Nbt.compoundElements(payload) ?: return payload
        val kept = ArrayList<ByteArray>(entries.size)
        for (entry in entries) {
            val fields = Nbt.fieldsOf(entry) ?: return payload
            val id = fields.firstOrNull { it.name == "id" }?.payload?.let(Nbt::stringOf)
            if (id == REINFORCEMENT_DICE) continue
            val modifiers = fields.firstOrNull { it.name == "modifiers" }
            val real = modifiers?.let { realModifiers(it.payload) }
            if (modifiers != null && real == null) {
                kept += entry
                continue
            }
            val base = fields.firstOrNull { it.name == "base" }?.payload?.let(Nbt::doubleOf)
            val atDefault = id != null && base != null && base == defaultOf(id)
            if (atDefault && real.isNullOrEmpty()) continue
            val edited = fields.mapNotNull { f ->
                when {
                    f.name != "modifiers" -> f
                    real.isNullOrEmpty() -> null
                    else -> NbtField(f.type, f.name, Nbt.listOf(NbtType.COMPOUND, real))
                }
            }
            kept += Nbt.bodyOf(edited)
        }
        return if (kept.isEmpty()) null else Nbt.listOf(NbtType.COMPOUND, kept)
    }

    private fun realModifiers(payload: ByteArray): List<ByteArray>? =
        Nbt.compoundElements(payload)?.filter { element ->
            val id = Nbt.fieldsOf(element)?.firstOrNull { it.name == "id" }?.payload?.let(Nbt::stringOf)
            id != SPAWN_DICE
        }

    private fun gunzip(bytes: ByteArray): ByteArray? {
        if (bytes.size < 18 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return null
        return runCatching { GZIPInputStream(bytes.inputStream()).use { it.readBytes() } }.getOrNull()
    }

    private fun gzip(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plain.size / 3 + 32)
        GZIPOutputStream(out).use { it.write(plain) }
        return out.toByteArray()
    }
}
