package com.tracel.plugin.i18n

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.minimessage.translation.Argument

/** A message by key, translated when it reaches a player. */
fun tr(key: String, vararg args: Pair<String, Any?>): Component =
    Component.translatable(Messages.PREFIX + key, args.map { (name, value) -> argument(name, value) })

/** Sends [tr] of [key]. */
fun Audience.send(key: String, vararg args: Pair<String, Any?>) = sendMessage(tr(key, *args))

private fun argument(name: String, value: Any?): ComponentLike = when (value) {
    is ComponentLike -> Argument.component(name, value)
    is Number -> Argument.numeric(name, value)
    is Boolean -> Argument.bool(name, value)
    else -> Argument.string(name, value.toString())
}
