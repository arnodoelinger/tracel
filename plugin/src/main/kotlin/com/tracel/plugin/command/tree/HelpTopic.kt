package com.tracel.plugin.command.tree

import com.tracel.plugin.command.permission.Permission

/**
 * The lines of `/tracel help`, in the order they are shown.
 *
 * @param permission who gets to see the line
 * @param key the message is `help.<key>`
 */
internal enum class HelpTopic(val permission: Permission, val key: String) {
    LOOKUP(Permission.LOOKUP, "lookup"),
    INSPECT(Permission.INSPECT, "inspect"),
    ROLLBACK(Permission.ROLLBACK, "rollback"),
    NEAR(Permission.LOOKUP, "near"),
    PLAYER(Permission.LOOKUP, "player"),
    PRESET(Permission.PRESET, "preset"),
    STATUS(Permission.STATUS, "status"),
    DATA(Permission.EXPORT, "data"),
}
