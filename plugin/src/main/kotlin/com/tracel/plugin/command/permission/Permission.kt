package com.tracel.plugin.command.permission

import org.bukkit.permissions.Permissible

/** Every permission `Tracel` asks for. */
enum class Permission(val node: String) {
    LOOKUP("tracel.lookup"),
    INSPECT("tracel.inspect"),
    NEAR("tracel.near"),
    PLAYER("tracel.player"),
    PRESET("tracel.preset"),
    PRESET_GLOBAL("tracel.preset.global"),
    ROLLBACK("tracel.rollback"),
    STATUS("tracel.status"),
    DATA("tracel.data"),
}

/** Whether this sender holds [permission]. */
fun Permissible.has(permission: Permission): Boolean = hasPermission(permission.node)
