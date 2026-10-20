package com.tracel.plugin.command.action

import com.tracel.api.result.Res
import com.tracel.plugin.command.action.support.describe
import com.tracel.plugin.command.presenter.StatusPresenter
import com.tracel.plugin.i18n.failed
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.integration.update.isDevelopmentBuild
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

/** `/tracel status`: how the plugin is doing, on one screen. */
class StatusAction(private val services: TracelServices) {
    /** Reads the status, then says it. A late capture queue is waited out for a few seconds so it can be measured. */
    fun execute(sender: CommandSender) {
        services.scope.launch {
            when (val status = services.commandApi.admin.status()) {
                is Res.Ok -> sender.say(
                    StatusPresenter.render(status.value, isDevelopmentBuild(services.plugin.pluginMeta.version)),
                )
                is Res.Err -> sender.failed(
                    "status.failed", Component.text(status.error.describe()), tr("common.hint.unknown"),
                )
            }
        }
    }
}
