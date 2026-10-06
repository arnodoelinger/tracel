package com.tracel.plugin.command.permission

import org.bukkit.permissions.Permissible

/** Every permission `Tracel` asks for. */
enum class Permission(val node: String) {
    LOOKUP("tracel.lookup"),
    INSPECT("tracel.inspect"),
    ROLLBACK("tracel.rollback"),
    PRESET("tracel.preset"),
    PRESET_GLOBAL("tracel.preset.global"),
    STATUS("tracel.status"),
    EXPORT("tracel.export"),
    PURGE("tracel.purge"),
    SETUP("tracel.setup"),
}

/** Whether this sender holds [permission]. */
fun Permissible.has(permission: Permission): Boolean = hasPermission(permission.node)
