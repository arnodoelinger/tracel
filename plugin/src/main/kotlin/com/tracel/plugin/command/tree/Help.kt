package com.tracel.plugin.command.tree

import com.tracel.plugin.command.permission.has
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin

/** Sends the lines of `/tracel help` this sender may use. */
internal fun sendHelp(sender: CommandSender, plugin: Plugin) {
    val meta = plugin.pluginMeta
    val lines = mutableListOf(
        tr("help.title", "version" to meta.version),
        tr("help.about", "author" to meta.authors.joinToString(", ")),
        Component.empty(),
    )
    for (topic in HelpTopic.entries) {
        if (sender.has(topic.permission)) lines += tr("help.${topic.key}")
    }
    lines += tr("help.help")
    sender.say(Component.join(JoinConfiguration.newlines(), lines))
}
