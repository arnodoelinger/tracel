package com.tracel.plugin.importer.coreprotect

import com.destroystokyo.paper.profile.ProfileProperty
import com.tracel.annotations.Unstable
import com.tracel.model.item.ItemKey
import com.tracel.model.world.WorldId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.model.world.OpaqueBytes
import io.papermc.paper.entity.EntitySerializationFlag
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.*
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.CommandBlock
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.Sign
import org.bukkit.block.banner.Pattern
import org.bukkit.block.sign.Side
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.configuration.serialization.DelegateDeserialization
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.*
import org.bukkit.potion.PotionEffect
import org.bukkit.util.io.BukkitObjectInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.ObjectInputFilter
import java.io.ObjectStreamClass
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/** The server this plugin runs on, as the translator needs it. */
object ServerImportPlatform : ImportPlatform {
    private const val SIDE_LINES = 4
    private const val PROFILE = "profile:"
    private const val MAX_NAME = 16
    private const val FIREWORK_PARTS = 3
    private const val KEPT_AGE = 0
    private const val KEPT_TAME = 1
    private const val KEPT_INFO = 2
    private const val KEPT_NAME_SHOWN = 3
    private const val KEPT_NAME = 4
    private const val VILLAGER_LEVEL = 3
    private const val VILLAGER_EXPERIENCE = 4
    private const val MAX_VILLAGER_LEVEL = 5

    private val TRUSTED = listOf(
        "java.lang.",
        "java.util.",
        "org.bukkit.",
        "com.google.common.collect.",
        LegacyRegistryValue::class.java.packageName + ".Legacy",
    )

    private val filter = ObjectInputFilter { info ->
        var type = info.serialClass() ?: return@ObjectInputFilter ObjectInputFilter.Status.UNDECIDED
        while (type.isArray) type = type.componentType
        if (type.isPrimitive || TRUSTED.any(type.name::startsWith)) ObjectInputFilter.Status.ALLOWED
        else ObjectInputFilter.Status.REJECTED
    }

    override fun world(name: String): WorldId? = Bukkit.getWorld(name)?.let { WorldId(it.uid) }

    override fun blockState(state: String): String? = runCatching { Bukkit.createBlockData(state).asString }.getOrNull()

    override fun entityType(name: String): String? {
        val key = NamespacedKey.fromString(name.lowercase()) ?: return null
        return runCatching { Registry.ENTITY_TYPE.get(key) }.getOrNull()?.key?.asString()
    }

    override fun player(name: String): UUID =
        Bukkit.getOfflinePlayerIfCached(name)?.uniqueId ?: UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray())

    override fun decode(blob: ByteArray): List<Any?>? = runCatching {
        LegacyInput(ByteArrayInputStream(blob)).use { input ->
            input.objectInputFilter = filter
            input.readObject() as? List<*>
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private class LegacyInput(input: InputStream) : BukkitObjectInputStream(input) {
        override fun readClassDescriptor(): ObjectStreamClass {
            val descriptor = super.readClassDescriptor()
            return LegacyRegistryValue.STAND_INS[descriptor.name]?.let(ObjectStreamClass::lookup) ?: descriptor
        }
    }

    @Suppress("UsePropertyAccessSyntax")
    override fun blockExtras(state: String, detail: BlockDetail): ByteArray? = runCatching {
        val data = Bukkit.createBlockData(state)
        val placed =
            data.placementMaterial.takeIf { it != Material.AIR && it.isItem } ?: data.material.takeIf { it.isItem }
        val carrier = ItemStack(placed ?: return@runCatching null)
        val meta = carrier.itemMeta
        when (detail) {
            is BlockDetail.Banner -> (meta as? BannerMeta ?: return@runCatching null).patterns =
                detail.patterns.mapNotNull { runCatching { Pattern(strings(it)) }.getOrNull() }

            is BlockDetail.Head -> head(meta as? SkullMeta ?: return@runCatching null, detail)
            else -> {
                val holder = meta as? BlockStateMeta ?: return@runCatching null
                val block = holder.blockState
                when (detail) {
                    is BlockDetail.Sign -> sign(block as? Sign ?: return@runCatching null, detail)
                    is BlockDetail.Command -> (block as? CommandBlock
                        ?: return@runCatching null).setCommand(detail.command)

                    is BlockDetail.Spawner -> (block as? CreatureSpawner ?: return@runCatching null).spawnedType =
                        NamespacedKey.fromString(detail.entity.lowercase())?.let(Registry.ENTITY_TYPE::get)
                            ?: return@runCatching null

                }
                holder.blockState = block
            }
        }
        carrier.itemMeta = meta
        carrier.serializeAsBytes()
    }.getOrNull()

    override fun itemKey(material: String, metadata: List<Any?>?): ItemKey? {
        val type = NamespacedKey.fromString(material)?.let(Registry.MATERIAL::get)?.takeIf { it.isItem } ?: return null
        val item = ItemStack(type)
        if (metadata != null) runCatching { dress(item, metadata) }
        return item.toItemKey()
    }

    override fun stack(entry: Any?): Pair<ItemKey, Int>? =
        runCatching { stackOf(entry)?.let { it.toItemKey() to it.amount } }.getOrNull()

    @Suppress("DEPRECATION")
    override fun entitySnapshot(
        world: WorldId,
        type: String,
        x: Double,
        y: Double,
        z: Double,
        kept: List<Any?>
    ): ByteArray? =
        runCatching {
            val home = Bukkit.getWorld(world.uuid) ?: return@runCatching null
            val kind =
                NamespacedKey.fromString(type)?.let(Registry.ENTITY_TYPE::get)?.entityClass ?: return@runCatching null
            val mob = home.createEntity(Location(home, x, y, z), kind)
            (mob as? LivingEntity)?.equipment?.clear()
            dress(mob, kept)
            Bukkit.getUnsafe().serializeEntity(mob, EntitySerializationFlag.FORCE)
                .takeIf { it.size <= OpaqueBytes.MAX_BYTES }
        }.getOrNull()

    private fun stackOf(entry: Any?): ItemStack? {
        val map = entry as? Map<*, *> ?: return null
        val bare = (map[0] ?: map["0"]) as? Map<*, *> ?: return null
        val item = ItemStack.deserialize(strings(bare))
        ((map[1] ?: map["1"]) as? List<*>)?.takeIf { it.isNotEmpty() }?.let { runCatching { dress(item, it) } }
        return item
    }

    @Suppress("DEPRECATION")
    private fun dress(mob: Entity, kept: List<Any?>) {
        val age = kept.getOrNull(KEPT_AGE) as? List<*>
        val tame = kept.getOrNull(KEPT_TAME) as? List<*>
        val info = (kept.getOrNull(KEPT_INFO) as? List<*>).orEmpty()
        if (mob is Ageable) (age?.getOrNull(0) as? Int)?.let { mob.age = it }
        if (mob is Breedable) (age?.getOrNull(1) as? Boolean)?.let { mob.ageLock = it }
        if (mob is Tameable && tame?.getOrNull(0) == true) {
            mob.isTamed = true
            (tame.getOrNull(1) as? String)?.let(Bukkit::getOfflinePlayerIfCached)?.let { mob.owner = it }
        }
        (kept.getOrNull(KEPT_NAME_SHOWN) as? Boolean)?.let { mob.isCustomNameVisible = it }
        (kept.getOrNull(KEPT_NAME) as? String)?.let {
            mob.customName(
                LegacyComponentSerializer.legacySection().deserialize(it)
            )
        }
        when (mob) {
            is Creeper -> (info.getOrNull(0) as? Boolean)?.let { mob.isPowered = it }
            is Sheep -> {
                (info.getOrNull(0) as? Boolean)?.let { mob.isSheared = it }
                (info.getOrNull(1) as? DyeColor)?.let { mob.color = it }
            }

            is Slime -> (info.getOrNull(0) as? Int)?.let { mob.size = it }
            is Phantom -> (info.getOrNull(0) as? Int)?.let { mob.size = it }
            is Wolf -> {
                (info.getOrNull(0) as? Boolean)?.let { mob.isSitting = it }
                (info.getOrNull(1) as? DyeColor)?.let { mob.collarColor = it }
            }

            is Cat -> {
                keyOf(info.getOrNull(0))?.let(Registry.CAT_VARIANT::get)?.let { mob.catType = it }
                (info.getOrNull(1) as? DyeColor)?.let { mob.collarColor = it }
                (info.getOrNull(2) as? Boolean)?.let { mob.isSitting = it }
            }

            is Villager -> {
                keyOf(info.getOrNull(0))?.let(Registry.VILLAGER_PROFESSION::get)?.let { mob.profession = it }
                keyOf(info.getOrNull(1))?.let(Registry.VILLAGER_TYPE::get)?.let { mob.villagerType = it }
                (info.getOrNull(VILLAGER_LEVEL) as? Int)?.takeIf { it in 1..MAX_VILLAGER_LEVEL }
                    ?.let { mob.villagerLevel = it }
                (info.getOrNull(VILLAGER_EXPERIENCE) as? Int)?.let { mob.villagerExperience = it }
            }

            is Zombie -> if (info.getOrNull(0) == true) mob.setBaby()
            else -> Unit
        }
    }

    private fun keyOf(kept: Any?): NamespacedKey? {
        val text =
            (kept as? LegacyRegistryValue)?.key ?: (kept as? Keyed)?.key?.toString() ?: kept?.toString() ?: return null
        return NamespacedKey.fromString(text.lowercase())
    }

    private fun sign(sign: Sign, detail: BlockDetail.Sign) {
        val legacy = LegacyComponentSerializer.legacySection()
        for (side in Side.entries) {
            val front = side == Side.FRONT
            val text = sign.getSide(side)
            val first = if (front) 0 else SIDE_LINES
            for (line in 0 until SIDE_LINES) text.line(
                line,
                legacy.deserialize(detail.lines.getOrElse(first + line) { "" })
            )
            val rgb = if (front) detail.color else detail.colorBack
            DyeColor.entries.firstOrNull { it.color.asRGB() == rgb }?.let { text.color = it }
            text.isGlowingText = if (front) detail.glowing else detail.glowingBack
        }
        sign.isWaxed = detail.waxed
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun head(meta: SkullMeta, detail: BlockDetail.Head) {
        val owner = detail.owner?.takeIf { it.isNotBlank() }
        val uuid = owner?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val skin = detail.skin?.takeIf { it.isNotBlank() }
        if (owner == null && skin == null) return
        val name = owner?.takeIf { uuid == null && it.length <= MAX_NAME }
        val profile = Bukkit.createProfile(
            uuid ?: if (name == null) UUID.nameUUIDFromBytes(
                (skin ?: owner!!).toByteArray()
            ) else null, name
        )
        if (skin != null && skin.startsWith(PROFILE)) {
            val parts = skin.removePrefix(PROFILE).split(':', limit = 2)
            profile.setProperty(ProfileProperty("textures", parts[0], parts.getOrNull(1)?.takeIf { it.isNotEmpty() }))
        } else if (skin != null) {
            val textures = profile.textures
            textures.skin = URI(skin).toURL()
            profile.setTextures(textures)
        }
        meta.playerProfile = profile
    }

    @Suppress("DEPRECATION", "USEPROPERTYACCESSSYNTAX")
    private fun dress(item: ItemStack, metadata: List<Any?>) {
        var burst: FireworkEffect.Builder? = null
        for ((part, entry) in metadata.withIndex()) {
            val maps = (entry as? List<*> ?: return).filterIsInstance<Map<*, *>>()
            val first = maps.firstOrNull()
            val explodes = item.itemMeta is FireworkMeta || item.itemMeta is FireworkEffectMeta
            when {
                first != null && (first["slot"] != null || first["facing"] != null) -> Unit
                first != null && first["modifiers"] != null -> modifiers(item, first["modifiers"] as? List<*>)
                explodes && part > 0 -> when ((part - 1) % FIREWORK_PARTS) {
                    0 -> burst = FireworkEffect.builder().apply {
                        (first?.get("type") as? FireworkEffect.Type)?.let(::with)
                        flicker(first?.get("flicker") == true)
                        trail(first?.get("trail") == true)
                    }

                    1 -> for (map in maps) burst?.withColor(Color.deserialize(strings(map)))
                    else -> {
                        for (map in maps) burst?.withFade(Color.deserialize(strings(map)))
                        runCatching { burst?.build() }.getOrNull()?.let { effect ->
                            val meta = item.itemMeta
                            if (meta is FireworkMeta) meta.addEffect(effect) else (meta as FireworkEffectMeta).effect =
                                effect
                            item.itemMeta = meta
                        }
                        burst = null
                    }
                }

                first == null -> Unit
                part == 0 -> {
                    item.itemMeta = own(item.itemMeta, strings(first)) ?: return
                    val meta = item.itemMeta
                    if (meta is PotionMeta && maps.size > 1) {
                        meta.color = Color.deserialize(strings(maps[1]))
                        item.itemMeta = meta
                    }
                }

                else -> {
                    val meta = item.itemMeta
                    for (map in maps) when (meta) {
                        is LeatherArmorMeta -> meta.setColor(Color.deserialize(strings(map)))
                        is PotionMeta -> meta.addCustomEffect(PotionEffect(strings(map)), true)
                        is BannerMeta -> meta.addPattern(Pattern(strings(map)))
                        is MapMeta -> meta.color = Color.deserialize(strings(map))
                        is SuspiciousStewMeta -> meta.addCustomEffect(PotionEffect(strings(map)), true)
                        else -> Unit
                    }
                    item.itemMeta = meta
                }
            }
        }
    }

    /** Attribute modifiers, each a one-entry map from the attribute to the modifier as `Bukkit` serializes it. */
    private fun modifiers(item: ItemStack, kept: List<*>?) {
        val meta = item.itemMeta
        for (entry in kept.orEmpty()) for ((attribute, modifier) in entry as? Map<*, *> ?: continue) {
            val known = keyOf(attribute)?.let(Registry.ATTRIBUTE::get) ?: continue
            runCatching {
                meta.addAttributeModifier(
                    known,
                    AttributeModifier.deserialize(strings(modifier as Map<*, *>))
                )
            }
        }
        item.itemMeta = meta
    }

    private fun own(like: ItemMeta, map: Map<String, Any>): ItemMeta? {
        val delegate = like.javaClass.getAnnotation(DelegateDeserialization::class.java) ?: return null
        return ConfigurationSerialization.deserializeObject(map, delegate.value.java) as? ItemMeta
    }

    @Suppress("UNCHECKED_CAST")
    private fun strings(map: Map<*, *>): Map<String, Any> = map as Map<String, Any>
}

/** Finds the database `CoreProtect` itself would open, by reading its folder the way it does. */
@Unstable
object CoreProtectLocator {
    private const val FOLDER = "CoreProtect"
    private const val DATABASE = "database.db"
    private const val DEFAULT_PORT = 3306

    fun find(plugins: Path): CoreProtectLocation? {
        val folder = plugins.resolve(FOLDER)
        val configFile = folder.resolve("config.yml")
        val config =
            if (Files.isRegularFile(configFile)) YamlConfiguration.loadConfiguration(configFile.toFile()) else null
        val prefix = config?.getString("table-prefix")?.takeIf { it.isNotBlank() } ?: CoreProtectLocation.DEFAULT_PREFIX
        if (config?.getBoolean("use-mysql") == true) {
            return CoreProtectLocation.Server(
                host = config.getString("mysql-host") ?: "127.0.0.1",
                port = config.getInt("mysql-port", DEFAULT_PORT),
                database = config.getString("mysql-database") ?: "database",
                user = config.getString("mysql-username") ?: "root",
                password = config.getString("mysql-password").orEmpty(),
                prefix = prefix,
            )
        }
        val file = folder.resolve(DATABASE)
        return if (Files.isRegularFile(file)) CoreProtectLocation.File(file, prefix) else null
    }
}
